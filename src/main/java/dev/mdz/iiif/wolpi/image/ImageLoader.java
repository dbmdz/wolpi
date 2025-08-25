package dev.mdz.iiif.wolpi.image;

import static app.photofox.vipsffm.VSource.newFromInputStream;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VCustomSource;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsSize;
import dev.mdz.iiif.wolpi.config.WolpiConfig;
import dev.mdz.iiif.wolpi.model.image.BinaryResolvedImage;
import dev.mdz.iiif.wolpi.model.image.CustomSourceResolvedImage;
import dev.mdz.iiif.wolpi.model.image.FilesystemResolvedImage;
import dev.mdz.iiif.wolpi.model.image.HttpResolvedImage;
import dev.mdz.iiif.wolpi.model.image.ImageInfo;
import dev.mdz.iiif.wolpi.model.image.ImageSize;
import dev.mdz.iiif.wolpi.model.image.ImageSource;
import dev.mdz.iiif.wolpi.model.image.TileSize;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/// ImageLoader is responsible for resolving and loading images from various sources.
@Component
public class ImageLoader {

  /// Memory [Arena] for libvips
  private final Arena arena;

  /// [HttpClient] for making requests to external image sources
  private final HttpClient httpClient;

  /// Base directory for images in the filesystem. This is used to resolve image paths when no
  /// extension can handle the request.
  private final Path imageBaseDirectory;

  public ImageLoader(WolpiConfig cfg, Arena arena, HttpClient httpClient) {
    this.arena = arena;
    this.httpClient = httpClient;
    this.imageBaseDirectory = cfg.imageBaseDir();
  }

  ///  Check if access to the image is authorized.
  /// @param identifier The identifier of the image to authorize access to.
  /// @param headers The HTTP headers of the request, which may contain authorization information.
  /// @param clientIp The IP address of the client making the request, after resolving any
  ///                 X-Forwarded-* headers.
  public boolean authorize(String identifier, Map<String, List<String>> headers, String clientIp) {
    // TODO: Call into extensions to authorize access to the image
    return true;
  }

  /// Resolve an image source by its identifier.
  public @Nullable ImageSource resolve(String identifier) {
    // TODO: Call into extensions to resolve the image source
    return this.resolveFromFilesystem(identifier);
  }

  /// Fallback implementation for resolving that resolves against the image base directory from
  /// the configuration.
  private @Nullable ImageSource resolveFromFilesystem(String identifier) {
    Path imagePath = imageBaseDirectory.resolve(identifier);
    if (imagePath.toFile().exists()) {
      return new ImageSource(identifier, new FilesystemResolvedImage(imagePath), null);
    }
    return null;
  }

  /// Get the image information for the given identifier.
  /// This will resolve the image source, and if the image source does not already provide image
  /// information, it will load the image and extract the information from it.
  public @Nullable ImageInfo getImageInfo(String identifier)
      throws IOException, InterruptedException {
    ImageSource source = resolve(identifier);
    if (source == null) {
      return null;
    }
    if (source.imageInfo() != null) {
      return source.imageInfo();
    }
    VImage image = loadImage(source);
    return getImageInfo(image);
  }

  /// Load an image from a source, applying shrink-on-load according to the target size.
  ///
  /// This can be much faster than loading the full image and then scaling it down, as it allows
  /// vips to exploit characteristics of the image format to load an already scaled-down version
  /// without applying any scaling itself.
  public VImage loadImage(ImageSource source, ImageSize targetSize)
      throws IOException, InterruptedException {
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
      case CustomSourceResolvedImage(VCustomSource src) ->
          VImage.thumbnailSource(
              arena,
              src,
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
    };
  }

  ///  Load the image in its native size from its source
  public VImage loadImage(ImageSource source) throws IOException, InterruptedException {
    return switch (source.resolvedImage()) {
      case FilesystemResolvedImage(Path path) ->
          VImage.newFromFile(arena, path.toAbsolutePath().toString());
      case HttpResolvedImage(
              URI uri,
              Map<String, String> headers,
              @Nullable Boolean supportsByteRange) ->
          loadFromHttp(uri, headers, null, supportsByteRange);
      case CustomSourceResolvedImage(VCustomSource src) -> VImage.newFromSource(arena, src);
      case BinaryResolvedImage(byte[] data) -> VImage.newFromBytes(arena, data);
    };
  }

  private VImage loadFromHttp(
      URI uri,
      @Nullable Map<String, String> headers,
      @Nullable ImageSize targetSize,
      @Nullable Boolean supportsByteRange)
      throws IOException, InterruptedException {
    // TODO: If the endpoint supports byte ranges, use a custom VSource implementation that
    //       leverages partial reads via byte-range requests
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(uri)
            .headers(
                headers != null
                    ? headers.entrySet().stream()
                        .flatMap(entry -> Stream.of(entry.getKey(), entry.getValue()))
                        .toArray(String[]::new)
                    : new String[0])
            .build();
    HttpResponse<InputStream> response =
        httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
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
        sizes.add(
            new ImageSize((int) (image.getWidth() * factor), (int) (image.getHeight() * factor)));
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
      tileSizes =
          List.of(
              new TileSize(
                  tileWidth,
                  tileHeight,
                  sizes.stream()
                      .map(size -> (int) Math.ceil(image.getWidth() / (double) size.width()))
                      .toList()));
    }

    return new ImageInfo(image.getWidth(), image.getHeight(), sizes, tileSizes, null);
  }
}
