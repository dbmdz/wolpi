package dev.mdz.wolpi.image;

import static app.photofox.vipsffm.VSource.newFromInputStream;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VSource;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsSize;
import dev.mdz.wolpi.config.IIIFConfig;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.exceptions.HttpStatusException;
import dev.mdz.wolpi.extension.ExtensionRuntime;
import dev.mdz.wolpi.iiif.IIIFComplianceRegistry;
import dev.mdz.wolpi.iiif.IIIFImageInfo;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.metrics.LoadType;
import dev.mdz.wolpi.metrics.WolpiMetrics;
import dev.mdz.wolpi.model.BinaryResolvedImage;
import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.CustomSourceResolvedImage;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSize;
import dev.mdz.wolpi.model.ImageSource;
import dev.mdz.wolpi.model.SourceNotModified;
import dev.mdz.wolpi.model.TileSize;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/// ImageLoader is responsible for resolving and loading images from various sources.
@Component
public class ImageLoader {

    /// Hardcoded identifier for official IIIF Image API Validation image
    private static final String VALIDATION_ID_PREFIX = "67352ccc-d1b0-11e1-89ae-279075081939";

    /// Pattern to extract the format name from a vips loader nickname
    private static final Pattern VIPS_LOADER_PAT = Pattern.compile("(?<format>[a-zA-Z0-9]+)load(?:_source|_buffer)?");

    /// Formats that support direct decoding of lower-resolution variants of the image
    private static final Set<String> SHRINK_ON_LOAD_FORMATS = Set.of("jp2k", "heif", "tiff");

    /// Threshold for the pixel difference between to subsequent layers, below means "likely pyramidal"
    /// Taken from libvips `resample/thumbnail.c`, L299-304 to match libvips vips_thumbnail logic
    private static final int PYRAMID_PIXEL_THRESHOLD = 5;

    private static final DateTimeFormatter IF_MODIFIED_SINCE_FORMATTER =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    ///  Request-scoped runtime for executing extension code.
    private final ExtensionRuntime extensionRuntime;

    /// Memory [Arena] for libvips
    private final Arena arena;

    /// [HttpClient] for making requests to external image sources
    private final HttpClient httpClient;

    /// Base directory for images in the filesystem. This is used to resolve image paths when no
    /// extension can handle the request.
    private final Path imageBaseDirectory;

    /// Tracks which IIIF compliance levels are supported, used for info.json generation
    private final IIIFComplianceRegistry complianceRegistry;

    /// IIIF settings from the configuration
    private final IIIFConfig iiifConfig;

    /// Metrics
    private final WolpiMetrics metrics;

    public ImageLoader(
            WolpiConfig cfg,
            Arena arena,
            HttpClient httpClient,
            ExtensionRuntime extensionRuntime,
            IIIFComplianceRegistry complianceRegistry,
            WolpiMetrics metrics) {
        this.arena = arena;
        this.httpClient = httpClient;
        this.imageBaseDirectory = cfg.imageBaseDir();
        this.iiifConfig = cfg.iiif();
        this.extensionRuntime = extensionRuntime;
        this.complianceRegistry = complianceRegistry;
        this.metrics = metrics;
    }

    /// Check if access to the image is authorized.
    /// @param identifier The identifier of the image to authorize access to.
    /// @param headers The HTTP headers of the request, which may contain authorization information.
    /// @param clientIp The IP address of the client making the request, after resolving any
    ///                 X-Forwarded-* headers.
    /// @return True if access is authorized, false otherwise.
    public boolean authorize(String identifier, Map<String, List<String>> headers, String clientIp) {
        if (identifier.startsWith(VALIDATION_ID_PREFIX)) {
            return true;
        }
        return extensionRuntime.authorize(identifier, headers, clientIp);
    }

    /// Resolve an image source by its identifier.
    ///
    /// If no extension can resolve the identifier, it will be resolved
    /// against the filesystem base directory from the configuration.
    ///
    /// @param identifier The identifier of the image to resolve.
    /// @param eTag      The ETag of the cached image on the client, if any.
    /// @param lastModified The last modified timestamp of the cached image on the client, if any.
    /// @return The resolved image source, or null if it could not be resolved.
    public @Nullable ImageSource resolve(String identifier, @Nullable String eTag, @Nullable Instant lastModified) {
        if (identifier.startsWith(VALIDATION_ID_PREFIX)) {
            metrics.incrementValidationRequests();
            return resolveValidationImage(identifier);
        }
        ImageSource source = extensionRuntime.resolve(identifier, eTag, lastModified);
        if (source == null) {
            source = this.resolveFromFilesystem(identifier);
        }
        if (source == null) {
            return null;
        }
        if (source.cacheInfo() == null && source.resolvedImage() instanceof FilesystemResolvedImage(Path path)) {
            try {
                source.setCacheInfo(CacheInfo.fromPath(path));
            } catch (IOException e) {
                log.warn(
                        "Failed to determine cache information from file path '{}' for {}",
                        path.toAbsolutePath(),
                        identifier);
            }
        }
        if (source.resolvedImage() instanceof HttpResolvedImage(URI url, @Nullable Map<String, String> httpHeaders)) {
            // Add HTTP caching headers, if not already set by the extension
            if (httpHeaders == null) {
                httpHeaders = new HashMap<>();
            }
            HttpHeaders parsedHeaders = new HttpHeaders(MultiValueMap.fromSingleValue(httpHeaders));
            Map<String, String> extraHeaders = new HashMap<>();
            if (eTag != null && !parsedHeaders.containsHeader("If-None-Match")) {
                extraHeaders.put("If-None-Match", eTag);
            }
            if (lastModified != null && !parsedHeaders.containsHeader("If-Modified-Since")) {
                extraHeaders.put("If-Modified-Since", IF_MODIFIED_SINCE_FORMATTER.format(lastModified));
            }
            if (!extraHeaders.isEmpty()) {
                extraHeaders.putAll(httpHeaders);
                return new ImageSource(
                        source.identifier(),
                        new HttpResolvedImage(url, extraHeaders),
                        source.imageInfo(),
                        source.cacheInfo());
            }
        }
        return source;
    }

    /// Fallback implementation for resolving that resolves against the image base directory from
    /// the configuration.
    private @Nullable ImageSource resolveFromFilesystem(String identifier) {
        Path imagePath = imageBaseDirectory.resolve(identifier);
        if (imagePath.toFile().exists()) {
            CacheInfo cacheInfo = null;
            try {
                cacheInfo = CacheInfo.fromPath(imagePath);
            } catch (IOException e) {
                log.warn("Failed to determine cache info from file path '{}' for {}", imagePath, identifier);
            }
            return new ImageSource(identifier, new FilesystemResolvedImage(imagePath), null, cacheInfo);
        }
        return null;
    }

    /// Load the official IIIF Image API Validation image from the classpath, either in PNG or JP2
    private @Nullable ImageSource resolveValidationImage(String identifier) {
        // Default format if no suffix is specified
        String format = "png";
        String[] parts = identifier.split("-");
        // Explicitly requested format via extra dashed component in identifier
        if (parts.length > 5) {
            String requestedFormat = parts[5].toLowerCase();
            if (requestedFormat.equals("png") || requestedFormat.equals("jp2")) {
                format = requestedFormat;
            } else {
                return null;
            }
        }
        String resourceName = "/test-img/%s.%s".formatted(VALIDATION_ID_PREFIX, format);

        CacheInfo cacheInfo = null;
        URL res = Objects.requireNonNull(getClass().getResource(resourceName));
        if ("file".equals(res.getProtocol())) {
            try {
                Path path = Path.of(res.toURI());
                cacheInfo = new CacheInfo(
                        null,
                        Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()));
            } catch (URISyntaxException | IOException e) {
                // NOP
            }
        } else if ("jar".equals(res.getProtocol())) {
            try {
                var conn = res.openConnection();
                var lastModified = conn.getLastModified();
                if (lastModified > 0) {
                    cacheInfo = new CacheInfo(null, Instant.ofEpochMilli(lastModified));
                }
            } catch (IOException e) {
                // NOP
            }
        }

        return new ImageSource(
                VALIDATION_ID_PREFIX,
                new CustomSourceResolvedImage((arena) -> VSource.newFromInputStream(
                        arena, Objects.requireNonNull(getClass().getResourceAsStream(resourceName)))),
                null,
                cacheInfo);
    }

    /// Get the image information for the given source.
    ///
    /// If the image source does not already provide image information, this will load the image and
    /// extract the information from it and update the source with it.
    public @Nullable ImageInfo getImageInfo(ImageSource source) {
        if (source.imageInfo() != null) {
            return source.imageInfo();
        }
        try {
            var image = loadImage(source);
            var info = getImageInfo(source, image);
            // Getting the image information is expensive, cache it
            source.setImageInfo(info);
            return info;
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to load image to obtain image information for id={}", source.identifier(), e);
            return null;
        }
    }

    /// Generate IIIF Image API info.json for the given image information and version.
    ///
    /// Will pass the info.json generation through suitable loaded extensions to allow them to augment
    /// the output.
    ///
    /// @param identifier The identifier of the image to generate the info.json for.
    /// @param imageInfo The image information to generate the info.json for.
    /// @param version The IIIF Image API version to generate the info.json for.
    /// @param imageBaseUrl The base URL of the image, used to construct the `id`/`@id` field
    /// @return A map representing the info.json structure, possibly augmented by extensions
    public Map<String, Object> getImageInfoJson(
            String identifier, ImageInfo imageInfo, IIIFVersion version, String imageBaseUrl) {
        Map<String, Object> infoJson =
                new IIIFImageInfo(imageInfo, iiifConfig, complianceRegistry).toJSON(version, imageBaseUrl);
        var augmented = extensionRuntime.augmentInfoJson(identifier, infoJson, version);
        return augmented != null ? augmented : infoJson;
    }

    /// Load an image from a source in a lower resolution, using one of the supported
    /// shrink-on-load patterns if possible.
    public VImage loadImage(ImageInfo info, ImageSource source, ImageSize targetSize)
            throws IOException, InterruptedException {
        if (SHRINK_ON_LOAD_FORMATS.contains(info.format())) {
            return loadImageUsingShrinkOnLoad(info, source, targetSize);
        } else {
            return loadThumbnailImage(source, targetSize);
        }
    }

    ///  Load the image in its native size from its source
    public VImage loadImage(ImageSource source) throws IOException, InterruptedException {
        VImage result = openImage(source);
        metrics.incrementSourceLoads(sourceTypeMetricLabel(source), LoadType.OPEN);
        return result;
    }

    public VImage loadImageUsingShrinkOnLoad(ImageInfo info, ImageSource source, ImageSize targetSize)
            throws IOException, InterruptedException {
        // Use shrink-on-load only (i.e. without additional downscaling) only if the format supports
        // it directly in good quality and the requested size matches one of the available sizes
        if (!SHRINK_ON_LOAD_FORMATS.contains(info.format()) || !info.sizes().contains(targetSize)) {
            return loadThumbnailImage(source, targetSize);
        }

        VipsOption[] loadOptions = getShrinkOnLoadOptions(info, source, targetSize);
        VImage image = openImage(source, loadOptions);
        if (targetSize.width() != image.getWidth() || targetSize.height() != image.getHeight()) {
            // Mismatch between assumed requested size and what we got back, can happen if the
            // TIFF wasn't actually pyramidal, we fall back to regular vips_thumbnail in that case
            log.warn(
                    "Requested precise shrink-on-load for image '{}' (fmt={}), but the loaded image "
                            + "size {}x{} does not match the requested target size {}x{}. Falling back to "
                            + "thumbnail loading.",
                    source.identifier(),
                    info.format(),
                    image.getWidth(),
                    image.getHeight(),
                    targetSize.width(),
                    targetSize.height());
            return loadThumbnailImage(source, targetSize);
        }

        metrics.incrementSourceLoads(sourceTypeMetricLabel(source), LoadType.SHRINK_ON_LOAD);
        return image;
    }

    private VipsOption[] getShrinkOnLoadOptions(ImageInfo info, ImageSource source, ImageSize targetSize)
            throws IOException, InterruptedException {
        int sizeIndex = info.sizes().stream()
                .sorted(Comparator.comparing(size -> -size.width() * size.height()))
                .toList()
                .indexOf(targetSize);
        if (sizeIndex <= 0) {
            return new VipsOption[] {};
        }
        // We checked against SHRINK_ON_LOAD_FORMATS already, so this should never be null
        assert info.format() != null;
        return switch (info.format()) {
            // TODO: Support OpenSlide via `level` option
            case "jp2k" -> new VipsOption[] {VipsOption.Int("page", sizeIndex)};
            case "tiff" -> {
                VImage image = openImage(source);
                if (image.getInt("n-pages") != null) {
                    yield new VipsOption[] {VipsOption.Int("page", sizeIndex)};
                } else if (image.getInt("n-subifds") != null) {
                    yield new VipsOption[] {VipsOption.Int("subifd", sizeIndex - 1)};
                } else {
                    yield new VipsOption[] {};
                }
            }
            case "heif" -> new VipsOption[] {VipsOption.Boolean("thumbnail", true)};
            default -> new VipsOption[] {};
        };
    }

    /// Loads a whole image through libvips' thumbnail API.
    private VImage loadThumbnailImage(ImageSource source, ImageSize targetSize)
            throws IOException, InterruptedException {
        var img =
                switch (source.resolvedImage()) {
                    case FilesystemResolvedImage(Path path) ->
                        VImage.thumbnail(
                                arena,
                                path.toString(),
                                targetSize.width(),
                                VipsOption.Int("height", targetSize.height()),
                                VipsOption.Enum("size", VipsSize.SIZE_FORCE));
                    case HttpResolvedImage(URI uri, Map<String, String> headers) ->
                        loadFromHttp(uri, headers, targetSize);
                    case CustomSourceResolvedImage(Function<Arena, VSource> srcSupplier) ->
                        VImage.thumbnailSource(
                                arena,
                                srcSupplier.apply(arena),
                                targetSize.width(),
                                VipsOption.Int("height", targetSize.height()),
                                VipsOption.Enum("size", VipsSize.SIZE_FORCE));
                    case BinaryResolvedImage(byte[] data) ->
                        VImage.thumbnailBuffer(
                                arena,
                                VBlob.newFromBytes(arena, data),
                                targetSize.width(),
                                VipsOption.Int("height", targetSize.height()),
                                VipsOption.Enum("size", VipsSize.SIZE_FORCE));
                    case SourceNotModified ignored ->
                        throw new IllegalArgumentException("Cannot load image from SourceNotModified images.");
                };
        metrics.incrementSourceLoads(sourceTypeMetricLabel(source), LoadType.THUMBNAIL);
        return img;
    }

    private VImage openImage(ImageSource source, VipsOption... options) throws IOException, InterruptedException {
        return switch (source.resolvedImage()) {
            case FilesystemResolvedImage(Path path) ->
                VImage.newFromFile(arena, path.toAbsolutePath().toString(), options);
            case HttpResolvedImage(URI uri, Map<String, String> headers) -> loadFromHttp(uri, headers, null, options);
            case CustomSourceResolvedImage(Function<Arena, VSource> srcSupplier) ->
                VImage.newFromSource(arena, srcSupplier.apply(arena), options);
            case BinaryResolvedImage(byte[] data) -> VImage.newFromBytes(arena, data, options);
            default -> throw new IllegalArgumentException("Cannot load image from unsupported resolved image.");
        };
    }

    /// Classifies the resolved source into the metric label used for load counters.
    private String sourceTypeMetricLabel(ImageSource source) {
        return switch (source.resolvedImage()) {
            case FilesystemResolvedImage ignored -> "filesystem";
            case HttpResolvedImage ignored -> "http";
            case CustomSourceResolvedImage ignored -> "custom";
            case BinaryResolvedImage ignored -> "binary";
            case SourceNotModified ignored ->
                throw new IllegalArgumentException("Cannot load image from SourceNotModified images.");
        };
    }

    /// Loads an HTTP source either directly or via libvips' thumbnail API, depending on whether a
    /// target size was requested.
    private VImage loadFromHttp(
            URI uri, @Nullable Map<String, String> headers, @Nullable ImageSize targetSize, VipsOption... options)
            throws IOException, InterruptedException {
        var reqBuilder = HttpRequest.newBuilder().uri(uri);
        if (headers != null) {
            headers.forEach(reqBuilder::header);
        }

        HttpResponse<InputStream> response = httpClient.send(reqBuilder.build(), BodyHandlers.ofInputStream());
        if (response.statusCode() == 304 || response.statusCode() >= 400) {
            if (response.statusCode() != 304) {
                log.warn("Failed to load image from '{}', received HTTP {}", uri, response.statusCode());
            }
            response.body().close();
            HttpHeaders responseHeaders = new HttpHeaders();
            if (response.headers() != null) {
                responseHeaders = new HttpHeaders(
                        MultiValueMap.fromMultiValue(response.headers().map()));
            }
            throw new HttpStatusException("Failed to load image", response.statusCode(), null, responseHeaders);
        }

        // NOTE: For all `newFrom*Stream` methods in vips-ffm, we know that the passed InputStream
        //       is closed when the lifetime of the associated FFM Arena has ended. In our case, the
        //       lifetime of this Arena is tied to the lifetime of the Request Scope in Spring, i.e.
        //       we can be sure that we don't leak InputStreams across request-response cycles.
        if (targetSize == null) {
            return VImage.newFromStream(arena, response.body(), options);
        }

        var src = newFromInputStream(arena, response.body());
        var thumbOptions = new VipsOption[options.length + 2];
        System.arraycopy(options, 0, thumbOptions, 0, options.length);
        thumbOptions[options.length] = VipsOption.Int("height", targetSize.height());
        thumbOptions[options.length + 1] = VipsOption.Enum("size", VipsSize.SIZE_FORCE);
        return VImage.thumbnailSource(arena, src, targetSize.width(), thumbOptions);
    }

    ///  Get image metadata from a loaded VImage.
    ImageInfo getImageInfo(ImageSource src, @Nullable VImage image) throws IOException, InterruptedException {
        // Check if the input image supports multiple resolution levels (e.g. TIFF, JPEG 2000)
        if (image == null) {
            image = openImage(src);
        }

        ImageSize nativeSize = new ImageSize(image.getWidth(), image.getHeight());
        List<ImageSize> sizes = new ArrayList<>();
        sizes.add(nativeSize);

        String loader = image.getString("vips-loader");
        if (loader.startsWith("heif")) {
            // HEIF Images can contain a lower-res thumbnail
            VImage thumbImage = openImage(src, VipsOption.Boolean("thumbnail", true));
            if (thumbImage.getWidth() < image.getWidth()) {
                sizes.add(new ImageSize(thumbImage.getWidth(), thumbImage.getHeight()));
            }
        } else if (loader.startsWith("tiff")) {
            sizes.addAll(getSupportedImageSizesTiff(image, src));
        } else if (loader.startsWith("jp2k") || loader.startsWith("kakadu")) {
            // For JPEG2000, lower-resolution pages in the same image are always pyramidal, so we don't need to probe
            // the source
            int nPages = image.getInt("n-pages");
            for (int i = 1; i < nPages; i++) {
                double factor = 1 / Math.pow(2, i);
                // libvips/OpenJPEG reduced pages round odd dimensions up, e.g. 1001 -> 501 -> 251.
                sizes.add(new ImageSize(
                        (int) Math.ceil(nativeSize.width() * factor), (int) Math.ceil(nativeSize.height() * factor)));
            }
        }

        var tileSizes = getSupportedTileSizes(image, sizes);
        return new ImageInfo(vipsLoaderToFormatString(loader.toLowerCase(Locale.ROOT)), nativeSize, sizes, tileSizes);
    }

    private List<ImageSize> getSupportedImageSizesTiff(VImage image, ImageSource src)
            throws IOException, InterruptedException {
        // TIFF images can be encoded as "pyramidal TIFs", either via multiple pages or via
        // SubIFDs, but not every TIFF with multiple pages or SubIFDs is pyramidal, so we have
        // to probe the source multiple times to verify we're dealing with a pyramidal TIFF.
        ImageSize nativeSize = new ImageSize(image.getWidth(), image.getHeight());
        Integer numPages = image.getInt("n-pages");
        Integer numSubIFDs = image.getInt("n-subifds");
        if (numPages == null && numSubIFDs != null) {
            return List.of();
        }
        String paramName = numPages != null ? "page" : "subifd";
        int startIdx = numPages != null ? 1 : 0;
        int endIdx = numPages != null ? numPages : numSubIFDs;
        List<ImageSize> candidateSizes = new ArrayList<>();
        for (int i = startIdx; i < endIdx; i++) {
            VImage reducedImage = openImage(src, VipsOption.Int(paramName, i));
            ImageSize size = new ImageSize(reducedImage.getWidth(), reducedImage.getHeight());
            ImageSize reference = i == startIdx ? nativeSize : candidateSizes.getLast();
            boolean isPyramidLevel = Math.abs(((double) reference.width() / 2) - size.width()) < PYRAMID_PIXEL_THRESHOLD
                    && Math.abs(((double) reference.height() / 2) - size.height()) < PYRAMID_PIXEL_THRESHOLD;
            if (!isPyramidLevel) {
                // Not a pyramidal TIF, do not consider the other SubIFDs/Pages as lower-res versions of the
                // same image
                candidateSizes.clear();
                break;
            }
            candidateSizes.add(new ImageSize(reducedImage.getWidth(), reducedImage.getHeight()));
        }
        return candidateSizes;
    }

    private List<TileSize> getSupportedTileSizes(VImage image, List<ImageSize> supportedSizes) {
        // Check if the image supports tiled loading
        // NOTE: Only works on libvips >= 8.18.0
        ImageSize nativeSize = new ImageSize(image.getWidth(), image.getHeight());
        Integer tileWidth = image.getInt("tile-width");
        Integer tileHeight = image.getInt("tile-height");
        if (tileWidth == null || tileHeight == null) {
            return List.of();
        } else {
            return List.of(new TileSize(
                    tileWidth,
                    tileHeight,
                    supportedSizes.stream()
                            .map(size -> (int) Math.round(nativeSize.width() / (double) size.width()))
                            .toList()));
        }
    }

    private static @Nullable String vipsLoaderToFormatString(String vipsLoader) {
        Matcher matcher = VIPS_LOADER_PAT.matcher(vipsLoader);
        if (!matcher.matches()) {
            log.warn("Unexpected vips loader '{}', cannot map to format!", vipsLoader);
            return null;
        }
        String format = matcher.group("format").toLowerCase(Locale.ROOT);
        // Kakadu JPEG 2000 loader identifies itself as "kakaduloader", but we want to report the format as "jp2k"
        if (format.equals("kakadu")) {
            format = "jp2k";
        }
        return format;
    }
}
