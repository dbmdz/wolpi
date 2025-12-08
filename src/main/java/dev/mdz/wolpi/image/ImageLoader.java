package dev.mdz.wolpi.image;

import static app.photofox.vipsffm.VSource.newFromInputStream;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VSource;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsSize;
import dev.mdz.wolpi.config.IIIFConfig;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.ExtensionRuntime;
import dev.mdz.wolpi.iiif.IIIFComplianceRegistry;
import dev.mdz.wolpi.iiif.IIIFImageInfo;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// ImageLoader is responsible for resolving and loading images from various sources.
@Component
public class ImageLoader {
    // Hardcoded identifier for official IIIF Image API Validation image
    private static final String VALIDATION_ID_PREFIX = "67352ccc-d1b0-11e1-89ae-279075081939";

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

    public ImageLoader(
            WolpiConfig cfg,
            Arena arena,
            HttpClient httpClient,
            ExtensionRuntime extensionRuntime,
            IIIFComplianceRegistry complianceRegistry) {
        this.arena = arena;
        this.httpClient = httpClient;
        this.imageBaseDirectory = cfg.imageBaseDir();
        this.iiifConfig = cfg.iiif();
        this.extensionRuntime = extensionRuntime;
        this.complianceRegistry = complianceRegistry;
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
            return resolveValidationImage(identifier);
        }
        ImageSource source = extensionRuntime.resolve(identifier, eTag, lastModified);
        if (source == null) {
            source = this.resolveFromFilesystem(identifier);
        }
        return source;
    }

    /// Fallback implementation for resolving that resolves against the image base directory from
    /// the configuration.
    private @Nullable ImageSource resolveFromFilesystem(String identifier) {
        Path imagePath = imageBaseDirectory.resolve(identifier);
        if (imagePath.toFile().exists()) {
            CacheInfo cacheInfo = new CacheInfo(
                    null,
                    imagePath.toFile().lastModified() > 0
                            ? Instant.ofEpochMilli(imagePath.toFile().lastModified())
                            : null);
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
    /// If the image source does not already provide image information, this will load the image and
    /// extract the information from it.
    public @Nullable ImageInfo getImageInfo(ImageSource source) {
        if (source.imageInfo() != null) {
            return source.imageInfo();
        }
        try {
            var image = loadImage(source);
            return getImageInfo(image);
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

    /// Load an image from a source, applying shrink-on-load according to the target size.
    ///
    /// This can be much faster than loading the full image and then scaling it down, as it allows
    /// vips to exploit characteristics of the image format to load an already scaled-down version
    /// without applying any scaling itself. The performance edge disappears when only requesting
    /// regions of an image, so in those cases the other overload of this method should be used.
    public VImage loadImage(ImageSource source, ImageSize targetSize) throws IOException, InterruptedException {
        return switch (source.resolvedImage()) {
            case FilesystemResolvedImage(Path path) ->
                VImage.thumbnail(
                        arena,
                        path.toString(),
                        targetSize.width(),
                        VipsOption.Int("height", targetSize.height()),
                        VipsOption.Enum("size", VipsSize.SIZE_FORCE));
            case HttpResolvedImage(URI uri, Map<String, String> headers, Boolean supportsByteRange) ->
                loadFromHttp(uri, headers, targetSize, supportsByteRange);
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
    }

    ///  Load the image in its native size from its source
    public VImage loadImage(ImageSource source) throws IOException, InterruptedException {
        return switch (source.resolvedImage()) {
            case FilesystemResolvedImage(Path path) ->
                VImage.newFromFile(arena, path.toAbsolutePath().toString());
            case HttpResolvedImage(URI uri, Map<String, String> headers, @Nullable Boolean supportsByteRange) ->
                loadFromHttp(uri, headers, null, supportsByteRange);
            case CustomSourceResolvedImage(Function<Arena, VSource> srcSupplier) ->
                VImage.newFromSource(arena, srcSupplier.apply(arena));
            case BinaryResolvedImage(byte[] data) -> VImage.newFromBytes(arena, data);
            case SourceNotModified ignored ->
                throw new IllegalArgumentException("Cannot load image from SourceNotModified images.");
        };
    }

    private VImage loadFromHttp(
            URI uri,
            @Nullable Map<String, String> headers,
            @Nullable ImageSize targetSize,
            @Nullable Boolean supportsByteRanges)
            throws IOException, InterruptedException {
        // TODO: If the endpoint supports byte ranges, use a custom VSource implementation that
        //       leverages partial reads via byte-range requests
        var reqBuilder = HttpRequest.newBuilder().uri(uri);
        if (headers != null) {
            reqBuilder = reqBuilder.headers(headers.entrySet().stream()
                    .flatMap(entry -> Stream.of(entry.getKey(), entry.getValue()))
                    .toArray(String[]::new));
        }
        HttpResponse<InputStream> response =
                httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw new IOException("Failed to load image from %s: %d".formatted(uri, response.statusCode()));
        }
        if (targetSize == null) {
            return VImage.newFromStream(arena, response.body());
        } else {
            var src = newFromInputStream(arena, response.body());
            return VImage.thumbnailSource(
                    arena,
                    src,
                    targetSize.width(),
                    VipsOption.Int("height", targetSize.height()),
                    VipsOption.Enum("size", VipsSize.SIZE_FORCE));
        }
    }

    ///  Get image metadata from a loaded VImage.
    public ImageInfo getImageInfo(VImage image) {
        // Check if the input image supports multiple resolution levels (e.g. TIFF, JPEG 2000)
        var hasPageInfo = image.getFields().contains("n-pages");
        List<ImageSize> sizes = new ArrayList<>();
        sizes.add(new ImageSize(image.getWidth(), image.getHeight()));
        if (hasPageInfo) {
            int nPages = image.getInt("n-pages");
            for (int i = 1; i < nPages; i++) {
                double factor = 1 / Math.pow(2, i);
                sizes.add(new ImageSize((int) (image.getWidth() * factor), (int) (image.getHeight() * factor)));
            }
        }

        // Check if the image supports tiled loading
        // NOTE: Only works on libvips builds that have https://github.com/libvips/libvips/pull/4637
        //       merged.
        List<TileSize> tileSizes;
        Integer tileWidth = image.getInt("tile-width");
        Integer tileHeight = image.getInt("tile-height");
        if (tileWidth == null || tileHeight == null) {
            tileSizes = List.of();
        } else {
            tileSizes = List.of(new TileSize(
                    tileWidth,
                    tileHeight,
                    sizes.stream()
                            .map(size -> (int) Math.ceil(image.getWidth() / (double) size.width()))
                            .toList()));
        }

        return new ImageInfo(new ImageSize(image.getWidth(), image.getHeight()), sizes, tileSizes);
    }
}
