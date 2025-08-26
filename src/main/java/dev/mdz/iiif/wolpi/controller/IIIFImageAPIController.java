package dev.mdz.iiif.wolpi.controller;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VipsError;
import dev.mdz.iiif.wolpi.config.WolpiConfig;
import dev.mdz.iiif.wolpi.iiif.IIIFImageInfo;
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

@Controller
public class IIIFImageAPIController {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final WolpiConfig config;
  private final ImageLoader loader;
  private final ImageProcessor processor;

  public IIIFImageAPIController(WolpiConfig config, ImageLoader loader, ImageProcessor processor) {
    this.config = config;
    this.loader = loader;
    this.processor = processor;
  }

  /// Return the IIIF Image Information for the given image identifier, using the specified version
  /// format.
  @GetMapping(value = "/{version}/{identifier}/info.json", produces = "application/json")
  public ResponseEntity<Map<String, Object>> getImageInfo(
      @PathVariable IIIFVersion version,
      @PathVariable String identifier,
      HttpHeaders headers,
      HttpServletRequest request)
      throws IOException, InterruptedException {
    if (!loader.authorize(identifier, headers, request.getRemoteAddr())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("error", "Unauthorized access to image"));
    }
    ImageSource source = loader.resolve(identifier);
    if (source == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Image not found"));
    }

    ImageInfo imageInfo =
        source.imageInfo() != null ? source.imageInfo() : loader.getImageInfo(identifier);
    if (imageInfo == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Image not found"));
    }
    Map<String, Object> rawInfo =
        new IIIFImageInfo(imageInfo, config.iiif())
            .toJSON(version, request.getRequestURL().toString().replace("/info.json", ""));

    HttpHeaders outHeaders = new HttpHeaders();
    if (config.iiif().features().jsonLdMediaType()) {
      outHeaders.setContentType(
          MediaType.valueOf(
              "application/ld+json;profile=\"http://iiif.io/api/image/%d/context.json\""
                  .formatted(version == IIIFVersion.V2 ? 2 : 3)));
    } else {
      outHeaders.setContentType(MediaType.APPLICATION_JSON);
    }
    if (config.iiif().features().cors()) {
      outHeaders.setAccessControlAllowOrigin("*");
    }
    if (!config.cacheControlHeaders().infoJson().isEmpty()) {
      outHeaders.setCacheControl(config.cacheControlHeaders().infoJson());
    }
    // TODO: ETag, Last-Modified
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
      HttpServletRequest servletRequest,
      HttpHeaders requestHeaders)
      throws IOException, InterruptedException {

    // Check permissions first
    if (!loader.authorize(identifier, requestHeaders, servletRequest.getRemoteAddr())) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
    }

    // See if the identifier actually resolves to something
    ImageSource source = loader.resolve(identifier);
    if (source == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .contentType(MediaType.TEXT_PLAIN)
          .body(ByteBuffer.wrap("Image not found".getBytes()));
    }

    ImageRequest request =
        new ImageRequest(
            identifier, version, regionSpec, sizeSpec, rotationSpec, colorSpec, formatSpec);

    // TODO: Canonical redirects, links
    VImage processedImage;
    try {
      processedImage = processor.processImage(source, request);
    } catch (NotImplementedException e) {
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
          .contentType(MediaType.TEXT_PLAIN)
          .body(ByteBuffer.wrap(e.getMessage().getBytes()));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .contentType(MediaType.TEXT_PLAIN)
          .body(ByteBuffer.wrap(e.getMessage().getBytes()));
    } catch (VipsError e) {
      log.error(
          "Error processing image request {} due to error in libvips", request.toRequestPath(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .contentType(MediaType.TEXT_PLAIN)
          .body(null);
    }

    // Build response headers based on configuration
    HttpHeaders outHeaders = new HttpHeaders();
    if (config.iiif().features().cors()) {
      outHeaders.setAccessControlAllowOrigin("*");
    }
    if (config.iiif().features().profileLinkHeader()) {
      outHeaders.add(
          "Link",
          "<http://iiif.io/api/image/%d/level2.json>; rel=\"profile\""
              .formatted(version == IIIFVersion.V2 ? 2 : 3));
    }
    if (!config.cacheControlHeaders().images().isEmpty()) {
      outHeaders.setCacheControl(config.cacheControlHeaders().images());
    }
    // TODO: ETag, Last-Modified
    var encoded = processor.encodeImage(processedImage, request.formatSpec());
    return ResponseEntity.ok()
        .headers(outHeaders)
        .contentType(MediaType.parseMediaType(encoded.contentType()))
        .contentLength(encoded.data().remaining())
        .body(encoded.data());
  }
}
