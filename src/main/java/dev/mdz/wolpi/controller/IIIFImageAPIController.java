package dev.mdz.wolpi.controller;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VipsError;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.iiif.ImageRequestParser;
import dev.mdz.wolpi.iiif.exceptions.NotImplementedException;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.iiif.model.ImageRequest;
import dev.mdz.wolpi.image.ImageLoader;
import dev.mdz.wolpi.image.ImageProcessor;
import dev.mdz.wolpi.metrics.WolpiMetrics;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSource;
import dev.mdz.wolpi.model.SourceNotModified;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class IIIFImageAPIController {

    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final WolpiConfig config;
    private final ImageLoader loader;
    private final ImageProcessor processor;
    private final ImageRequestParser imageRequestParser;
    private final WolpiMetrics metrics;

    public IIIFImageAPIController(
            WolpiConfig config,
            ImageLoader loader,
            ImageProcessor processor,
            ImageRequestParser imageRequestParser,
            WolpiMetrics metrics) {
        this.config = config;
        this.loader = loader;
        this.processor = processor;
        this.imageRequestParser = imageRequestParser;
        this.metrics = metrics;
    }

    /// Handle OPTIONS requests for the /info.json endpoint
    @RequestMapping(
            value = "/{version}/{identifier}/info.json",
            method = {RequestMethod.OPTIONS})
    public ResponseEntity<Void> optionsImageInfo(@RequestHeader HttpHeaders requestHeaders) {
        return createOptionsResponse(requestHeaders);
    }

    /// Redirect requests to the base URI of an image (without /info.json) to the info.json endpoint,
    /// if configured to do so.
    @GetMapping(value = "/{version}/{identifier}", produces = "application/json")
    public ResponseEntity<Void> baseUriRedirect(@PathVariable IIIFVersion version, @PathVariable String identifier) {
        if (!config.iiif().features().baseUriRedirect()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        HttpHeaders headers = new HttpHeaders();
        String redirectUrl =
                ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString() + "/info.json";
        headers.add("Location", redirectUrl);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).headers(headers).build();
    }

    /// Return the IIIF Image Information for the given image identifier, using the specified version
    /// format.
    @GetMapping(
            value = "/{version}/{identifier}/info.json",
            produces = {"application/json", "application/ld+json"})
    public ResponseEntity<Map<String, Object>> getImageInfo(
            @PathVariable IIIFVersion version,
            @PathVariable String identifier,
            @Nullable @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @Nullable @RequestHeader(value = "If-Modified-Since", required = false) Instant ifModifiedSince,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request,
            WebRequest webRequest) {
        HttpHeaders outHeaders = new HttpHeaders();
        if (config.iiif().features().cors()) {
            outHeaders.setAccessControlAllowOrigin(
                    Optional.ofNullable(headers.getOrigin()).orElse("*"));
        }
        MultiValueMap<String, String> headersMap = new LinkedMultiValueMap<>();
        headers.forEach(headersMap::put);
        if (!loader.authorize(identifier, headersMap, request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .headers(outHeaders)
                    .body(Map.of("error", "Unauthorized access to image"));
        }

        ImageSource source = loader.resolve(identifier, ifNoneMatch, ifModifiedSince);
        if (source == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .headers(outHeaders)
                    .body(Map.of("error", "Image not found"));
        } else if (source.resolvedImage() instanceof SourceNotModified) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .headers(outHeaders)
                    .body(null);
        }

        if (source.cacheInfo() != null) {
            boolean notModified = false;
            if (source.cacheInfo().lastModified() != null) {
                notModified = webRequest.checkNotModified(
                        source.cacheInfo().eTag(),
                        source.cacheInfo().lastModified().toEpochMilli());
            } else if (source.cacheInfo().eTag() != null) {
                notModified = webRequest.checkNotModified(source.cacheInfo().eTag());
            }
            if (notModified) {
                outHeaders.setCacheControl(config.cacheControlHeaders().infoJson());
                setCacheHeaders(outHeaders, source);
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).body(null);
            }
        }

        ImageInfo imageInfo = loader.getImageInfo(source);
        if (imageInfo == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .headers(outHeaders)
                    .body(Map.of("error", "Could not read image information"));
        }

        String baseUrl;
        if (hasConfiguredBaseUri()) {
            baseUrl = "%s/v%d/%s".formatted(config.http().baseUri(), version.value(), identifier);
        } else {
            baseUrl = request.getRequestURL().toString().replace("/info.json", "");
        }
        Map<String, Object> infoJson = loader.getImageInfoJson(identifier, imageInfo, version, baseUrl);

        if (config.iiif().features().jsonLdMediaType()) {
            outHeaders.setContentType(
                    MediaType.valueOf("application/ld+json;profile=\"http://iiif.io/api/image/%d/context.json\""
                            .formatted(version == IIIFVersion.V2 ? 2 : 3)));
        } else {
            outHeaders.setContentType(MediaType.APPLICATION_JSON);
        }
        outHeaders.setCacheControl(config.cacheControlHeaders().infoJson());
        setCacheHeaders(outHeaders, source);
        return ResponseEntity.ok().headers(outHeaders).body(infoJson);
    }

    /// Handle OPTIONS requests for the image processing endpoint
    @RequestMapping(
            value = "/{version}/{identifier}/{region}/{size}/{rotation}/{quality}.{format}",
            method = {RequestMethod.OPTIONS})
    public ResponseEntity<Void> optionsImage(@RequestHeader HttpHeaders requestHeaders) {
        return createOptionsResponse(requestHeaders);
    }

    /// Process the image according to the IIIF Image API request in the URL.
    @GetMapping(value = "/{version}/{identifier}/{region}/{size}/{rotation}/{quality}.{format}")
    public ResponseEntity<ByteBuffer> getImage(
            @PathVariable String identifier,
            @PathVariable IIIFVersion version,
            @PathVariable("region") String regionSpec,
            @PathVariable("size") String sizeSpec,
            @PathVariable("rotation") String rotationSpec,
            @PathVariable("quality") String colorSpec,
            @PathVariable("format") String formatSpec,
            @Nullable @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @Nullable @RequestHeader(value = "If-Modified-Since", required = false) Instant ifModifiedSince,
            @RequestHeader HttpHeaders requestHeaders,
            HttpServletRequest servletRequest,
            WebRequest webRequest)
            throws IOException, InterruptedException {

        HttpHeaders outHeaders = new HttpHeaders();
        // CORS needs to be set for all response types
        if (config.iiif().features().cors()) {
            outHeaders.setAccessControlAllowOrigin(
                    Optional.ofNullable(requestHeaders.getOrigin()).orElse("*"));
        }

        // Check permissions first
        MultiValueMap<String, String> requestHeadersMap = new LinkedMultiValueMap<>();
        requestHeaders.forEach(requestHeadersMap::put);
        if (!loader.authorize(identifier, requestHeadersMap, servletRequest.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .headers(outHeaders)
                    .body(null);
        }

        // See if the identifier actually resolves to something
        ImageSource source = loader.resolve(identifier, ifNoneMatch, ifModifiedSince);
        if (source == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .headers(outHeaders)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ByteBuffer.wrap("Image not found".getBytes()));
        } else if (source.resolvedImage() instanceof SourceNotModified) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .headers(outHeaders)
                    .body(null);
        }

        // Check client headers if source has caching info, maybe we don't have to process the request?
        if (source.cacheInfo() != null) {
            boolean notModified = false;
            if (source.cacheInfo().lastModified() != null) {
                notModified = webRequest.checkNotModified(
                        source.cacheInfo().eTag(),
                        source.cacheInfo().lastModified().toEpochMilli());
            } else if (source.cacheInfo().eTag() != null) {
                notModified = webRequest.checkNotModified(source.cacheInfo().eTag());
            }

            if (notModified) {
                setCacheHeaders(outHeaders, source);
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .headers(outHeaders)
                        .body(null);
            }
        }

        ImageInfo imageInfo = loader.getImageInfo(source);
        if (imageInfo == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .headers(outHeaders)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ByteBuffer.wrap("Could not read image information".getBytes()));
        }

        // Determine canonical form of the request
        ImageRequest request =
                new ImageRequest(identifier, version, regionSpec, sizeSpec, rotationSpec, colorSpec, formatSpec);
        ImageRequest canonicalRequest = imageRequestParser.toCanonicalForm(request, imageInfo.nativeSize());

        // Check if the request has its canonical form, and if not, redirect to it, if configured to do
        // so
        if (canonicalRequest != null
                && !request.equals(canonicalRequest)
                && config.iiif().features().canonicalRedirect()) {
            // Canonical redirects tracked by Spring Boot as 302s
            String canonicalUrl = canonicalRequest.toRequestPath();
            outHeaders.add("Location", canonicalUrl);
            return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                    .headers(outHeaders)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ByteBuffer.wrap(("See: " + canonicalUrl).getBytes()));
        }

        // Time to actually process the image
        VImage processedImage;
        try {
            processedImage = processor.processImage(source, request);
        } catch (NotImplementedException e) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .headers(outHeaders)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ByteBuffer.wrap(e.getMessage().getBytes()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .headers(outHeaders)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ByteBuffer.wrap(e.getMessage().getBytes()));
        } catch (VipsError e) {
            metrics.incrementVipsErrors("image_processing");
            String requestPath = canonicalRequest != null ? canonicalRequest.toRequestPath() : request.toRequestPath();
            log.error("Error processing image request {} due to error in libvips", requestPath, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .headers(outHeaders)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(null);
        }

        List<String> linkHeaderUrls = new ArrayList<>();
        if (canonicalRequest != null && config.iiif().features().canonicalLinkHeader()) {
            String canonicalUrl;
            if (hasConfiguredBaseUri()) {
                canonicalUrl = "%s%s".formatted(config.http().baseUri(), canonicalRequest.toRequestPath());
            } else {
                canonicalUrl = ServletUriComponentsBuilder.fromCurrentRequest()
                        .build()
                        .toUriString()
                        .replace(request.toRequestPath(), canonicalRequest.toRequestPath());
            }
            linkHeaderUrls.add("<%s>; rel=\"canonical\"".formatted(canonicalUrl));
        }

        // Cache Headers only on proper image responses
        outHeaders.setCacheControl(config.cacheControlHeaders().images());
        setCacheHeaders(outHeaders, source);

        if (config.iiif().features().profileLinkHeader()) {
            linkHeaderUrls.add("<http://iiif.io/api/image/%d/level2.json>; rel=\"profile\""
                    .formatted(version == IIIFVersion.V2 ? 2 : 3));
        }

        if (!linkHeaderUrls.isEmpty()) {
            outHeaders.add(HttpHeaders.LINK, String.join(", ", linkHeaderUrls));
        }

        // Encode the processed image to the requested output format and return it to the client
        try {
            var encoded = processor.encodeImage(processedImage, imageInfo, request);
            metrics.incrementImagesProcessed(formatSpec, colorSpec, version);
            if (encoded.extraHeaders() != null) {
                encoded.extraHeaders()
                        .forEach((header, values) -> values.forEach(value -> outHeaders.add(header, value)));
            }
            return ResponseEntity.ok()
                    .headers(outHeaders)
                    .contentType(MediaType.parseMediaType(encoded.contentType()))
                    .contentLength(encoded.data().remaining())
                    .body(encoded.data());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .headers(outHeaders)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ByteBuffer.wrap(e.getMessage().getBytes()));
        }
    }

    /// Set `ETag` and/or `Last-Modified` headers on the given HttpHeaders object based on the
    /// cache information in the [ImageSource].
    ///
    /// Will only set the headers if they are not already present in the existing header set.
    ///
    /// @param headers           The [HttpHeaders] object to set the headers on.
    /// @param source            The [ImageSource] containing cache information.
    private void setCacheHeaders(HttpHeaders headers, ImageSource source) {
        if (source.cacheInfo() == null) {
            return;
        }
        if (source.cacheInfo().eTag() != null && !headers.containsHeader("ETag")) {
            headers.setETag("\"%s\"".formatted(source.cacheInfo().eTag()));
        }
        if (source.cacheInfo().lastModified() != null && !headers.containsHeader("Last-Modified")) {
            headers.setLastModified(source.cacheInfo().lastModified());
        }
    }

    /// Generate an OPTIONS response with allowed methods and CORS headers if enabled.
    private ResponseEntity<Void> createOptionsResponse(HttpHeaders requestHeaders) {
        HttpHeaders outHeaders = new HttpHeaders();
        outHeaders.setAccessControlAllowMethods(List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS));
        if (config.iiif().features().cors()) {
            // Explicitly allow the origin that made the request
            outHeaders.setAccessControlAllowOrigin(
                    Optional.ofNullable(requestHeaders.getOrigin()).orElse("*"));

            // Allow all requested headers, and "Accept" by default
            List<String> reqHeaders = requestHeaders.getAccessControlRequestHeaders();
            if (!reqHeaders.isEmpty()) {
                outHeaders.setAccessControlAllowHeaders(reqHeaders);
            } else {
                outHeaders.setAccessControlAllowHeaders(List.of("Accept"));
            }
            outHeaders.setAccessControlMaxAge(86400L);
        }
        return ResponseEntity.ok().headers(outHeaders).build();
    }

    private boolean hasConfiguredBaseUri() {
        return config.http() != null
                && config.http().baseUri() != null
                && !config.http().baseUri().isBlank();
    }
}
