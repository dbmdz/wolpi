package dev.mdz.iiif.wolpi.image;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VTarget;
import app.photofox.vipsffm.VipsHelper;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsDirection;
import app.photofox.vipsffm.enums.VipsInterpretation;
import app.photofox.vipsffm.enums.VipsOperationRelational;
import app.photofox.vipsffm.enums.VipsSize;
import dev.mdz.iiif.wolpi.config.WolpiConfig;
import dev.mdz.iiif.wolpi.iiif.ImageRequestParser;
import dev.mdz.iiif.wolpi.iiif.NotImplementedException;
import dev.mdz.iiif.wolpi.model.image.EncodedImage;
import dev.mdz.iiif.wolpi.model.image.ImageSize;
import dev.mdz.iiif.wolpi.model.image.ImageSource;
import dev.mdz.iiif.wolpi.model.params.IIIFQuality;
import dev.mdz.iiif.wolpi.model.params.ImageRequest;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/// ImageProcessor is responsible for processing images according to IIIF Image API requests.
///
/// It handles the whole pipeline of scaling, cropping, rotating, color conversion and output
/// encoding via libvips in an efficient way.
@Component
public class ImageProcessor {
  private final Arena vipsArena;
  private final WolpiConfig wolpiConfig;

  private final ImageRequestParser parser;
  private final ImageLoader loader;

  public ImageProcessor(
      Arena vipsArena, WolpiConfig wolpiConfig, ImageLoader loader, ImageRequestParser parser) {
    this.vipsArena = vipsArena;
    this.wolpiConfig = wolpiConfig;
    this.loader = loader;
    this.parser = parser;
  }

  /// Process the image from the given ImageSource according to the provided ImageRequest.
  /// @param imageSource Source to load the image from
  /// @param request unparsed IIIF Image API request
  public VImage processImage(ImageSource imageSource, ImageRequest request)
      throws IOException, InterruptedException, NotImplementedException {
    // Pre-load the image (just the header) if neccessary to determine the native dimensiosn
    VImage image = null;
    ImageSize sourceSize;
    if (imageSource.imageInfo() != null) {
      sourceSize =
          new ImageSize(
              imageSource.imageInfo().nativeWidth(), imageSource.imageInfo().nativeHeight());
    } else {
      image = loader.loadImage(imageSource);
      sourceSize = new ImageSize(image.getWidth(), image.getHeight());
    }

    // Crop and Scale
    if (parser.isRequestForUncroppedAndDownScaledImage(request.cropSpec(), request.sizeSpec())) {
      // Fast path: No cropping, only downscaling, we can make use of libvips' "shrink-on-load"
      // feature for supported image formats like JP2 and TIFF.
      // Benchmarks showed that this is the only case among the common IIIF use cases, where
      // shrink-on-load actually gives a performance benefit. For use cases involving cropping,
      // shrink-on-load actually resulted in worse performance.
      image =
          loader.loadImage(
              imageSource, parser.parseSize(request.version(), request.sizeSpec(), sourceSize));
    } else {
      if (image == null) {
        image = loader.loadImage(imageSource);
      }
      var cropRectangle = parser.parseRegion(request.cropSpec(), sourceSize);
      VImage cropped;
      if (cropRectangle.width() == sourceSize.width()
          && cropRectangle.height() == sourceSize.height()) {
        cropped = image;
      } else {
        cropped =
            image.extractArea(
                cropRectangle.x(),
                cropRectangle.y(),
                cropRectangle.width(),
                cropRectangle.height());
      }

      var scaledSize = parser.parseSize(request.version(), request.sizeSpec(), sourceSize);
      VImage scaled;
      if (cropRectangle.width() == scaledSize.width()
          || cropRectangle.height() == scaledSize.height()) {
        scaled = cropped;
      } else {
        scaled =
            cropped.thumbnailImage(
                scaledSize.width(),
                VipsOption.Int("height", scaledSize.height()),
                VipsOption.Enum("size", VipsSize.SIZE_FORCE));
      }
      image = scaled;
    }

    VImage rotated = image;
    var rotation = parser.parseRotation(request.rotationSpec());
    if (rotation.mirror() || rotation.degrees() != 0.0) {
      // Apply mirroring and rotation
      if (rotation.mirror()) {
        rotated = rotated.flip(VipsDirection.DIRECTION_VERTICAL);
      }
      if (rotation.degrees() != 0.0) {
        rotated = rotated.rotate(rotation.degrees());
      }
    }

    String qualitySpec = request.qualitySpec();
    if (qualitySpec.equalsIgnoreCase("default")) {
      qualitySpec = wolpiConfig.iiif().qualities().defaultQuality();
    }
    IIIFQuality quality = parser.parseQuality(qualitySpec);

    var outputInterpretation =
        switch (quality) {
          case COLOR -> VipsInterpretation.INTERPRETATION_sRGB;
          case GRAY -> VipsInterpretation.INTERPRETATION_GREY16;
          case BITONAL -> VipsInterpretation.INTERPRETATION_B_W;
        };
    // FIXME: There should be high-level API in vips-ffm for this, but there isn't yet
    int sourceInterpretation = VipsHelper.image_get_interpretation(image.getUnsafeStructAddress());
    if (outputInterpretation.getRawValue() != sourceInterpretation) {
      return rotated.colourspace(outputInterpretation);
    } else {
      return rotated;
    }
  }

  /// Encode an image to a target format.
  public EncodedImage encodeImage(VImage image, String suffix) throws IOException {
    List<VipsOption> options = new ArrayList<>();

    // Force 1bit output for bitonal images in PNG and GIF formats
    if (image.getInt("interpretation") == VipsInterpretation.INTERPRETATION_B_W.getRawValue()) {
      if (suffix.equals("png") || suffix.equals("gif")) {
        options.add(VipsOption.Int("bitdepth", 1));
      } else {
        // Other formats do not perform binarization themselves, so we need to threshold the image
        image =
            image.relationalConst(
                VipsOperationRelational.OPERATION_RELATIONAL_MORE, List.of(128.0));
      }
    }

    String mimeType = Files.probeContentType(Paths.get("image.%s".formatted(suffix)));
    VTarget writeTarget = VTarget.newToMemory(vipsArena);
    image.writeToTarget(writeTarget, ".%s".formatted(suffix), options.toArray(new VipsOption[0]));
    return new EncodedImage(
        writeTarget.getBlob().asArenaScopedByteBuffer(),
        mimeType != null ? mimeType : "image/%s".formatted(suffix));
  }
}
