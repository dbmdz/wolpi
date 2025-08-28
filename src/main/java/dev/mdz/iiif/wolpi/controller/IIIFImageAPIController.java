package dev.mdz.iiif.wolpi.controller;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VipsError;
import dev.mdz.iiif.wolpi.config.WolpiConfig;
import dev.mdz.iiif.wolpi.iiif.IIIFImageInfo;
import dev.mdz.iiif.wolpi.iiif.ImageRequestParser;
import dev.mdz.iiif.wolpi.iiif.NotImplementedException;
import dev.mdz.iiif.wolpi.image.ImageLoader;
import dev.mdz.iiif.wolpi.image.ImageProcessor;
import dev.mdz.iiif.wolpi.model.image.ImageInfo;
import dev.mdz.iiif.wolpi.model.image.ImageSource;
import dev.mdz.iiif.wolpi.model.params.IIIFVersion;
import dev.mdz.iiif.wolpi.model.params.ImageRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
public class IIIFImageAPIController {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final WolpiConfig config;
  private final ImageLoader loader;
  private final ImageProcessor processor;
  private final ImageRequestParser imageRequestParser;

  public IIIFImageAPIController(
      WolpiConfig config,
      ImageLoader loader,
      ImageProcessor processor,
      ImageRequestParser imageRequestParser) {
    this.config = config;
    this.loader = loader;
    this.processor = processor;
    this.imageRequestParser = imageRequestParser;
  }

  /// Return the IIIF Image Information for the given image identifier, using the specified version
  /// format.
  @GetMapping(value = "/{version}/{identifier}/info.json", produces = "application/json")
  public ResponseEntity<Map<String, Object>> getImageInfo(
      @PathVariable IIIFVersion version,
      @PathVariable String identifier,
      HttpHeaders headers,
      HttpServletRequest request,
      WebRequest webRequest) {
    HttpHeaders outHeaders = new HttpHeaders();
    if (config.iiif().features().cors()) {
      outHeaders.setAccessControlAllowOrigin("*");
    }
    if (!loader.authorize(identifier, headers, request.getRemoteAddr())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .headers(outHeaders)
          .body(Map.of("error", "Unauthorized access to image"));
    }
    ImageSource source = loader.resolve(identifier);
    if (source == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .headers(outHeaders)
          .body(Map.of("error", "Image not found"));
    }

    if (source.cacheInfo() != null) {
      boolean notModified = false;
      if (source.cacheInfo().lastModified() != null) {
        notModified =
            webRequest.checkNotModified(
                source.cacheInfo().eTag(), source.cacheInfo().lastModified().toEpochMilli());
      } else if (source.cacheInfo().eTag() != null) {
        notModified = webRequest.checkNotModified(source.cacheInfo().eTag());
      }
      if (notModified) {
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

    Map<String, Object> rawInfo =
        new IIIFImageInfo(imageInfo, config.iiif())
            .toJSON(version, request.getRequestURL().toString().replace("/info.json", ""));

    if (config.iiif().features().jsonLdMediaType()) {
      outHeaders.setContentType(
          MediaType.valueOf(
              "application/ld+json;profile=\"http://iiif.io/api/image/%d/context.json\""
                  .formatted(version == IIIFVersion.V2 ? 2 : 3)));
    } else {
      outHeaders.setContentType(MediaType.APPLICATION_JSON);
    }
    setCacheHeaders(outHeaders, source);
    return ResponseEntity.ok().headers(outHeaders).body(rawInfo);
  }

  /// Process the image according to the IIIF Image API request in the URL.
  @GetMapping(value = "/{version}/{identifier}/{scale}/{size}/{rotation}/{color}.{format}")
  public ResponseEntity<ByteBuffer> getImage(
      @PathVariable String identifier,
      @PathVariable IIIFVersion version,
      @PathVariable("scale") String regionSpec,
      @PathVariable("size") String sizeSpec,
      @PathVariable("rotation") String rotationSpec,
      @PathVariable("color") String colorSpec,
      @PathVariable("format") String formatSpec,
      HttpHeaders requestHeaders,
      HttpServletRequest servletRequest,
      WebRequest webRequest)
      throws IOException, InterruptedException {

    HttpHeaders outHeaders = new HttpHeaders();
    // CORS needs to be set for all response types
    if (config.iiif().features().cors()) {
      outHeaders.setAccessControlAllowOrigin("*");
    }

    // Check permissions first
    if (!loader.authorize(identifier, requestHeaders, servletRequest.getRemoteAddr())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).headers(outHeaders).body(null);
    }

    // See if the identifier actually resolves to something
    ImageSource source = loader.resolve(identifier);
    if (source == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .headers(outHeaders)
          .contentType(MediaType.TEXT_PLAIN)
          .body(ByteBuffer.wrap("Image not found".getBytes()));
    }

    // Check client headers if source has caching info, maybe we don't have to process the request?
    if (source.cacheInfo() != null) {
      boolean notModified = false;
      if (source.cacheInfo().lastModified() != null) {
        notModified =
            webRequest.checkNotModified(
                source.cacheInfo().eTag(), source.cacheInfo().lastModified().toEpochMilli());
      } else if (source.cacheInfo().eTag() != null) {
        notModified = webRequest.checkNotModified(source.cacheInfo().eTag());
      }

      if (notModified) {
        setCacheHeaders(outHeaders, source);
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(outHeaders).body(null);
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
        new ImageRequest(
            identifier, version, regionSpec, sizeSpec, rotationSpec, colorSpec, formatSpec);
    ImageRequest canonicalRequest =
        imageRequestParser.toCanonicalForm(request, imageInfo.nativeSize());

    // Check if the request has its canonical form, and if not, redirect to it, if configured to do
    // so
    if (canonicalRequest != null
        && !request.equals(canonicalRequest)
        && config.iiif().features().canonicalRedirect()) {
      // Redirect to canonical URL
      String canonicalUrl = canonicalRequest.toRequestPath();
      outHeaders.add("Location", canonicalUrl);
      return ResponseEntity.status(HttpStatus.SEE_OTHER)
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
      String requestPath =
          canonicalRequest != null ? canonicalRequest.toRequestPath() : request.toRequestPath();
      log.error("Error processing image request {} due to error in libvips", requestPath, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .headers(outHeaders)
          .contentType(MediaType.TEXT_PLAIN)
          .body(null);
    }

    if (canonicalRequest != null && config.iiif().features().canonicalLinkHeader()) {
      String canonicalUrl =
          ServletUriComponentsBuilder.fromCurrentRequest()
              .build()
              .toUriString()
              .replace(request.toRequestPath(), canonicalRequest.toRequestPath());
      outHeaders.add("Link", "<%s>; rel=\"canonical\"".formatted(canonicalUrl));
    }

    // Cache Headers only on proper image responses
    setCacheHeaders(outHeaders, source);

    if (config.iiif().features().profileLinkHeader()) {
      outHeaders.add(
          "Link",
          "<http://iiif.io/api/image/%d/level2.json>; rel=\"profile\""
              .formatted(version == IIIFVersion.V2 ? 2 : 3));
    }

    // Encode the processed image to the requested output format and return it to the client
    var encoded = processor.encodeImage(processedImage, request.formatSpec());
    return ResponseEntity.ok()
        .headers(outHeaders)
        .contentType(MediaType.parseMediaType(encoded.contentType()))
        .contentLength(encoded.data().remaining())
        .body(encoded.data());
  }

  /// Set cache-related headers on the response according to the configuration and the image
  /// source's cache info.
  private void setCacheHeaders(HttpHeaders headers, ImageSource source) {
    if (!config.cacheControlHeaders().infoJson().isEmpty()) {
      headers.setCacheControl(config.cacheControlHeaders().infoJson());
    }
    if (source.cacheInfo() != null) {
      if (source.cacheInfo().eTag() != null && !headers.containsKey("ETag")) {
        headers.setETag("\"%s\"".formatted(source.cacheInfo().eTag()));
      }
      if (source.cacheInfo().lastModified() != null && !headers.containsKey("Last-Modified")) {
        headers.setLastModified(source.cacheInfo().lastModified());
      }
    }
  }
}
