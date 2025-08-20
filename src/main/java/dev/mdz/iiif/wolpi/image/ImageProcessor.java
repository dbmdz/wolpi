package dev.mdz.iiif.wolpi.image;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VTarget;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsDirection;
import app.photofox.vipsffm.enums.VipsInterpretation;
import app.photofox.vipsffm.enums.VipsOperationRelational;
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
    // For performance reasons, we deviate a bit from the IIIF Image API spec:
    // In the specification, cropping always happens before scaling. However, this is not ideal from
    // a performance perspective, as we cannot make use of the shrink-on-load feature of libvips[1]
    // if we first crop and only then scale (see docstring for `VImage#thumbnailImage`)
    // Instead, we first scale the image to the target size, then crop it to the requested region,
    // which requires some transformations to the crop rectangle and scaling specification.
    // [1] https://github.com/libvips/libvips/wiki/HOWTO----Image-shrinking#opening-images

    // To parse scaling and cropping requests, we need to know the reference size of the input
    // image, so either get it from the ImageSource (if available) or load the image to determine
    // its size.
    ImageSize sourceSize;
    VImage unscaledSource = null;
    if (imageSource.imageInfo() != null) {
      sourceSize =
          new ImageSize(
              imageSource.imageInfo().nativeWidth(), imageSource.imageInfo().nativeHeight());
    } else {
      unscaledSource = loader.loadImage(imageSource);
      sourceSize = new ImageSize(unscaledSource.getWidth(), unscaledSource.getHeight());
    }
    var croppedAndScaledSize = parser.parseSize(request.version(), request.sizeSpec(), sourceSize);
    var unscaledCropRectangle = parser.parseRegion(request.cropSpec(), sourceSize);

    // Transform crop rectangle and scaling target size so we can apply the crop after scaling.
    var uncroppedScaledSize =
        parser.computeUncroppedSize(sourceSize, unscaledCropRectangle, croppedAndScaledSize);
    var scaledCropRectangle =
        parser.scaleCropToTargetSize(unscaledCropRectangle, sourceSize, uncroppedScaledSize);

    VImage scaled;
    if (sourceSize.equals(uncroppedScaledSize)) {
      scaled = unscaledSource != null ? unscaledSource : loader.loadImage(imageSource);
    } else {
      scaled = loader.loadImage(imageSource, uncroppedScaledSize);
    }

    VImage cropped;
    if (scaledCropRectangle.width() == scaled.getWidth()
        || scaledCropRectangle.height() == scaled.getHeight()) {
      cropped = scaled;
    } else {
      cropped =
          scaled.extractArea(
              scaledCropRectangle.x(),
              scaledCropRectangle.y(),
              scaledCropRectangle.width(),
              scaledCropRectangle.height());
    }

    VImage rotated;
    var rotation = parser.parseRotation(request.rotationSpec());
    if (rotation.mirror() || rotation.degrees() != 0.0) {
      rotated = cropped;
      // Apply mirroring and rotation
      if (rotation.mirror()) {
        rotated = rotated.flip(VipsDirection.DIRECTION_VERTICAL);
      }
      if (rotation.degrees() != 0.0) {
        rotated = rotated.rotate(rotation.degrees());
      }
    } else {
      // No rotation needed
      rotated = cropped;
    }

    String qualitySpec = request.qualitySpec();
    if (qualitySpec.equalsIgnoreCase("default")) {
      qualitySpec = wolpiConfig.iiif().qualities().defaultQuality();
    }
    IIIFQuality quality = parser.parseQuality(qualitySpec);

    return rotated.colourspace(
        switch (quality) {
          case COLOR -> VipsInterpretation.INTERPRETATION_sRGB;
          case GRAY -> VipsInterpretation.INTERPRETATION_GREY16;
          case BITONAL -> VipsInterpretation.INTERPRETATION_B_W;
        });
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
