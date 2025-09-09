package dev.mdz.wolpi.iiif;

import dev.mdz.wolpi.config.IIIFConfig;
import dev.mdz.wolpi.config.IIIFConfig.RegionFeature;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.iiif.model.CropRectangle;
import dev.mdz.wolpi.iiif.model.IIIFQuality;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.iiif.model.ImageRequest;
import dev.mdz.wolpi.iiif.model.Rotation;
import dev.mdz.wolpi.image.ImageProcessor;
import dev.mdz.wolpi.model.ImageSize;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/// Parser for IIIF Image API Requests (v2 and v3).
///
/// Intended to be used from [ImageProcessor] to parse parts of the request
/// as they are needed. We do not do any pre-parsing, since we want to give extensions a chance to
/// handle custom syntax, so the parsing (and validation) is done on-demand when we know that no
/// extension is handling the syntax for the part of the request.
@Component
public class ImageRequestParser {

  private final IIIFConfig iiifConfig;

  public ImageRequestParser(WolpiConfig config) {
    this.iiifConfig = config.iiif();
  }

  /// Parses the rotation specification from the request.
  ///
  /// There are two formats permissible for specifying the rotation, with `n` a floating point
  /// number between 0 and 360:
  ///
  /// - `n`: The degrees of clockwise rotation
  /// - `!n`: Mirror the image on the vertical axis before rotating it
  ///
  /// @param rotationSpec The rotation specification as a string, formatted according to the IIIF
  ///                     Image API v2 or v3 specification.
  /// @return A [Rotation] object representing the parsed rotation.
  /// @throws IllegalArgumentException if the rotation specification is invalid or unsupported.
  public Rotation parseRotation(String rotationSpec) {
    String spec = rotationSpec.trim();
    boolean mirror = false;
    if (spec.startsWith("!")) {
      if (!iiifConfig.features().rotation().mirroring()) {
        throw new IllegalArgumentException("Mirroring is not supported in this configuration.");
      }
      mirror = true;
      spec = spec.substring(1);
    }

    if (!"0".equals(rotationSpec)
        && !iiifConfig.features().rotation().by90DegreeRotation()
        && !iiifConfig.features().rotation().arbitrary()) {
      throw new IllegalArgumentException("Rotation is not supported in this configuration.");
    }

    double degrees;
    try {
      degrees = Double.parseDouble(spec);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid rotation specification: " + rotationSpec, e);
    }

    degrees = degrees % 360.0;
    if (degrees < 0) {
      degrees += 360.0;
    }

    if (!iiifConfig.features().rotation().arbitrary() && degrees % 90.0 != 0) {
      throw new IllegalArgumentException(
          "Arbitrary rotation is not supported in this configuration.");
    }

    return new Rotation(degrees, mirror);
  }

  /// Parses the quality specification from the request.
  ///
  /// Value can be one of the following:
  /// - `color`
  /// - `gray`
  /// - `bitonal`
  ///
  /// The spec permits `default` as well, but this should be handled by callers and mapped to one of
  /// the above values.
  ///
  /// The spec also permits custom qualities, these are not mapped here, instead extensions should
  /// take the raw quality string and handle it as needed.
  public IIIFQuality parseQuality(String qualitySpec) {
    return IIIFQuality.fromString(qualitySpec);
  }

  /// Parses the region specification from the request.
  ///
  /// The region can be expressed in several ways:
  /// - `full`: The full image, no cropping
  /// - `square`: A square crop at an implementation-defined location in the center of the image
  /// - `x,y,w,h`: A pixel-based crop, where `x` and `y` are the top-left coordinates of the crop,
  ///              and `w` and `h` are the width and height of the crop, expressed in absolute,
  ///              non-fractional pixels.
  ///  - `pct:x,y,w,h`: A percent-based crop, where `x` and `y` are the top-left coordinates of the
  ///                   crop expressed as a percentage of the image size, and `w` and `h` are the
  ///                   width and height of the crop expressed as a percentage of the image size.
  ///                   Percentages can be integral or floating point values between 0 and 100.
  ///
  /// @param cropSpec   The crop specification as a string, formatted according to the IIIF Image
  ///                   API v2 or v3 specification.
  /// @param sourceSize The source image size to use as the reference coordinate system for the
  ///                   crop.
  /// @return A [CropRectangle] object representing the cropped region in relation to the source
  ///         image size.
  /// @throws IllegalArgumentException if the crop specification is invalid or unsupported.
  public CropRectangle parseRegion(String cropSpec, ImageSize sourceSize) {
    int nativeWidth = sourceSize.width();
    int nativeHeight = sourceSize.height();
    if ("full".equalsIgnoreCase(cropSpec)) {
      return new CropRectangle(0, 0, nativeWidth, nativeHeight);
    }

    RegionFeature supported = iiifConfig.features().region();

    if (!supported.square() && !supported.byPercent() && !supported.byPixels()) {
      throw new IllegalArgumentException("Region cropping is not supported in this configuration.");
    }

    if ("square".equalsIgnoreCase(cropSpec)) {
      // Use the center square crop as the default for square cropping.
      if (!supported.square()) {
        throw new IllegalArgumentException(
            "Square cropping is not supported in this configuration.");
      }
      int smallestSide = Math.min(nativeWidth, nativeHeight);
      int x = (nativeWidth - smallestSide) / 2;
      int y = (nativeHeight - smallestSide) / 2;
      return new CropRectangle(x, y, smallestSide, smallestSide);
    }

    if (cropSpec.toLowerCase().startsWith("pct:")) {
      if (!supported.byPercent()) {
        throw new IllegalArgumentException(
            "Percent-based cropping is not supported in this configuration.");
      }

      String[] parts = cropSpec.substring(4).split(",");
      if (parts.length != 4) {
        throw new IllegalArgumentException("Invalid percent-based crop specification: " + cropSpec);
      }
      double px = Double.parseDouble(parts[0]);
      double py = Double.parseDouble(parts[1]);
      double pw = Double.parseDouble(parts[2]);
      double ph = Double.parseDouble(parts[3]);

      if (px < 0 || px > 100 || py < 0 || py > 100 || pw < 0 || pw > 100 || ph < 0 || ph > 100) {
        throw new IllegalArgumentException("Percent values must be between 0 and 100.");
      }
      if (px + pw > 100 || py + ph > 100) {
        throw new IllegalArgumentException("Crop exceeds image dimensions.");
      }
      if (pw <= 0 || ph <= 0) {
        throw new IllegalArgumentException("Width and height must be greater than 0.");
      }

      return new CropRectangle(
          (int) Math.floor(nativeWidth * (px / 100.0)),
          (int) Math.floor(nativeHeight * (py / 100.0)),
          (int) Math.floor(nativeWidth * (pw / 100.0)),
          (int) Math.floor(nativeHeight * (ph / 100.0)));
    } else {
      if (!supported.byPixels()) {
        throw new IllegalArgumentException(
            "Pixel-based cropping is not supported in this configuration.");
      }
      String[] parts = cropSpec.split(",");
      if (parts.length != 4) {
        throw new IllegalArgumentException("Invalid pixel-based crop specification: " + cropSpec);
      }
      int x = Integer.parseInt(parts[0]);
      int y = Integer.parseInt(parts[1]);
      int w = Integer.parseInt(parts[2]);
      int h = Integer.parseInt(parts[3]);
      if (x < 0 || y < 0 || w < 0 || h < 0) {
        throw new IllegalArgumentException("Crop coordinates and dimensions must be non-negative.");
      }
      if (x > nativeWidth || y > nativeHeight) {
        throw new IllegalArgumentException("Crop coordinates exceed image dimensions.");
      }

      if (x + w > nativeWidth || y + h > nativeHeight) {
        throw new IllegalArgumentException("Crop exceeds image dimensions.");
      }

      return new CropRectangle(x, y, w, h);
    }
  }

  /// Parses the size specification from the request.
  ///
  /// The size can be expressed in several ways:
  /// - `full`: Only for IIIF v2, the full image, no scaling
  /// - `max`: The maximum size of the image, scaled down to fit within the limits defined in the
  /// IIIF configuration. This is the native size of the image if no limits are defined in the
  /// configuration.
  /// - `^max`: Same as `max`, but upscales the image to fit within the limits defined in the
  /// configuration if its native size is smaller than the limits.
  /// - `w,`:   Scale to the given width, maintaining the aspect ratio.
  /// - `^w,`:  Scale to the given width, maintaining the aspect ratio, but upscaling the image if
  /// its native size is smaller than the given width.
  /// - `,h`:   Scale to the given height, maintaining the aspect ratio.
  /// - `^,h`:  Scale to the given height, maintaining the aspect ratio, but upscaling the image if
  /// its native size is smaller than the given
  /// - `pct:n`: Scale the image by the given percentage, where `n` is a floating point number
  /// between 0 and 100.
  /// - `^pct:n`: Same as `pct:n`, but allows values greater than 100 to upscale the image
  /// - `w,h`:  Scale the image to the given width and height, ignoring the aspect ratio.
  /// - `^w,h`: Same as `w,h`, but allows upscaling the image if its native size is smaller than the
  /// given width and height.
  /// - `!w,h`:  Scale the image to the given width and height, but confine the scaling to the
  /// specified width and height, maintaining the aspect ratio.
  /// - `^!w,h`: Same as `!w,h`, but allows upscaling the image if its native size is smaller than
  /// the given width and height.
  ///
  /// @param iiifVersion The IIIF version to use for parsing the size specification, v2 and v3
  ///                    differ in a few points
  /// @param sizeSpec    The size specification as a string, formatted according to the IIIF Image
  ///                    API v2 or v3 specification.
  /// @param sourceSize  The unscaled source image size
  /// @return A [ImageSize] object representing the parsed size.
  /// @throws IllegalArgumentException if the size specification is invalid or unsupported in the
  ///                                  selected IIIF Image API version.
  /// @throws NotImplementedException  if upscaling is requested, but not supported in the
  ///                                  configuration
  public ImageSize parseSize(IIIFVersion iiifVersion, String sizeSpec, ImageSize sourceSize)
      throws NotImplementedException {
    IIIFConfig.ScalingFeature supported = iiifConfig.features().scaling();
    IIIFConfig.Limits limits = iiifConfig.limits();

    if (iiifVersion == IIIFVersion.V2) {
      if (sizeSpec.startsWith("^")) {
        throw new IllegalArgumentException(
            "The caret (^) prefix is not supported in IIIF v2, use the IIIF v3 endpoint.");
      }
    } else if (iiifVersion == IIIFVersion.V3) {
      if ("full".equals(sizeSpec)) {
        throw new IllegalArgumentException(
            "The 'full' size specification is not valid in IIIF v3, use 'max' instead or use the IIIF v2 endpoint.");
      }
    }

    boolean doUpscale = sizeSpec.startsWith("^");
    if (doUpscale) {
      sizeSpec = sizeSpec.substring(1);
    }

    ImageSize result = new ImageSize(sourceSize.width(), sourceSize.height());
    if ("full".equals(sizeSpec) || "max".equals(sizeSpec) || "^max".equals(sizeSpec)) {
      if (limits.maxWidth() > 0) {
        double scale = limits.maxWidth() / (double) result.width();
        if (result.width() > limits.maxWidth()
            || (doUpscale && result.width() < limits.maxWidth())) {
          result =
              new ImageSize(
                  (int) Math.max(1, Math.floor(result.width() * scale)),
                  (int) Math.max(1, Math.floor(result.height() * scale)));
        }
      }

      if (limits.maxHeight() > 0) {
        double scale = limits.maxHeight() / (double) result.height();
        if (result.height() > limits.maxHeight()
            || (doUpscale && result.height() < limits.maxHeight())) {
          result =
              new ImageSize(
                  (int) Math.max(1, Math.floor(result.width() * scale)),
                  (int) Math.max(1, Math.floor(result.height() * scale)));
        }
      }

      if (limits.maxArea() > 0 && (long) result.width() * result.height() > limits.maxArea()) {
        double scale = Math.sqrt(limits.maxArea() / (double) (result.width() * result.height()));
        result =
            new ImageSize(
                (int) Math.max(1, Math.floor(result.width() * scale)),
                (int) Math.max(1, Math.floor(result.height() * scale)));
      }
    } else if (sizeSpec.endsWith(",")) {
      if (!supported.byWidth()) {
        throw new IllegalArgumentException(
            "Width-based scaling is not supported in this configuration.");
      }
      int width = Integer.parseInt(sizeSpec.substring(0, sizeSpec.length() - 1));
      if (width <= 0) {
        throw new IllegalArgumentException("Width must be > 0: " + sizeSpec);
      }
      double scale = (double) width / result.width();
      result = new ImageSize(width, (int) Math.max(1, Math.floor(result.height() * scale)));
    } else if (sizeSpec.startsWith(",") || sizeSpec.startsWith("^,")) {
      if (!supported.byHeight()) {
        throw new IllegalArgumentException(
            "Height-based scaling is not supported in this configuration.");
      }
      int height = Integer.parseInt(sizeSpec.substring(1));
      if (height <= 0) {
        throw new IllegalArgumentException("Height must be > 0: " + sizeSpec);
      }
      double scale = (double) height / result.height();
      result = new ImageSize((int) Math.max(1, Math.floor(result.width() * scale)), height);
    } else if (sizeSpec.startsWith("pct:") || sizeSpec.startsWith("^pct:")) {
      if (!supported.byPercent()) {
        throw new IllegalArgumentException(
            "Percent-based scaling is not supported in this configuration.");
      }
      double scale = Double.parseDouble(sizeSpec.substring(4)) / 100.0;
      if (scale <= 0 || scale > 1) {
        throw new IllegalArgumentException("Scale must be >=0 and <=100: " + sizeSpec);
      }
      result =
          new ImageSize(
              (int) Math.max(1, Math.floor(result.width() * scale)),
              (int) Math.max(1, Math.floor(result.height() * scale)));
    } else if (sizeSpec.startsWith("!") || sizeSpec.startsWith("^!")) {
      if (!supported.byConfinedWidthHeight()) {
        throw new IllegalArgumentException(
            "Confined width and height scaling is not supported in this configuration.");
      }
      String[] parts = sizeSpec.substring(1).split(",");
      if (parts.length != 2) {
        throw new IllegalArgumentException(
            "Invalid confined width and height specification: " + sizeSpec);
      }
      int maxWidth = Integer.parseInt(parts[0]);
      int maxHeight = Integer.parseInt(parts[1]);
      if (maxWidth <= 0 || maxHeight <= 0) {
        throw new IllegalArgumentException("Width and height must be > 0: " + sizeSpec);
      }
      double scaleX = (double) maxWidth / result.width();
      double scaleY = (double) maxHeight / result.height();
      double scale = Math.min(scaleX, scaleY);
      result =
          new ImageSize(
              (int) Math.max(1, Math.floor(result.width() * scale)),
              (int) Math.max(1, Math.floor(result.height() * scale)));
    } else {
      if (!supported.byArbitraryDimensions()) {
        throw new IllegalArgumentException(
            "Arbitrary dimensions scaling is not supported in this configuration.");
      }
      String[] parts = sizeSpec.split(",");
      if (parts.length != 2) {
        throw new IllegalArgumentException("Invalid size specification: " + sizeSpec);
      }
      int width = Integer.parseInt(parts[0]);
      int height = Integer.parseInt(parts[1]);
      if (width <= 0 || height <= 0) {
        throw new IllegalArgumentException("Width and height must be > 0: " + sizeSpec);
      }
      result = new ImageSize(width, height);
    }

    boolean wasUpscaled =
        (result.width() > sourceSize.width() || result.height() > sourceSize.height());
    if (wasUpscaled) {
      if (doUpscale && !supported.allowUpscaling()) {
        throw new NotImplementedException("Upscaling is not supported in this configuration.");
      } else if (!doUpscale && supported.allowUpscaling()) {
        throw new IllegalArgumentException(
            "Size requests that exceed the native image size must be prefixed with '^'");
      } else if (!doUpscale) {
        throw new IllegalArgumentException("Upscaling is not supported in this configuration");
      }
    }

    if (limits.maxWidth() > 0 && result.width() > limits.maxWidth()) {
      throw new IllegalArgumentException(
          "Requested width exceeds maximum allowed width: " + limits.maxWidth());
    }
    if (limits.maxHeight() > 0 && result.height() > limits.maxHeight()) {
      throw new IllegalArgumentException(
          "Requested height exceeds maximum allowed height: " + limits.maxHeight());
    }
    if (limits.maxArea() > 0 && (long) result.width() * result.height() > limits.maxArea()) {
      throw new IllegalArgumentException(
          "Requested area exceeds maximum allowed area: " + limits.maxArea());
    }
    return result;
  }

  public boolean isRequestForUncroppedAndDownScaledImage(String regionSpec, String sizeSpec) {
    return "full".equals(regionSpec)
        && !("full".equals(sizeSpec) || "max".equals(sizeSpec) || sizeSpec.startsWith("^"));
  }

  /// Converts a given image request to its canonical form, based on the source image size.
  ///
  /// This can be used to determine if two different requests would result in the same image, and to
  /// generate canonical URLs for images.
  ///
  /// @param request    The original [ImageRequest].
  /// @param sourceSize The size of the source image.
  /// @return A new ImageRequest representing the canonical form of the request, or `null` if the
  ///         request cannot be canonicalized (e.g. due to non-standard parameters).
  public @Nullable ImageRequest toCanonicalForm(ImageRequest request, ImageSize sourceSize) {
    boolean isV2 = request.version() == IIIFVersion.V2;
    try {
      ImageSize parsedSize = this.parseSize(request.version(), request.sizeSpec(), sourceSize);
      CropRectangle parsedRegion = this.parseRegion(request.cropSpec(), sourceSize);
      String canonicalRegion =
          parsedRegion.x() == 0
                  && parsedRegion.y() == 0
                  && new ImageSize(parsedRegion.width(), parsedRegion.height()).equals(sourceSize)
              ? "full"
              : "%d,%d,%d,%d"
                  .formatted(
                      parsedRegion.x(),
                      parsedRegion.y(),
                      parsedRegion.width(),
                      parsedRegion.height());
      String canonicalSize;
      boolean sizeIsMax =
          parsedSize.width() == iiifConfig.limits().maxWidth()
              || parsedSize.height() == iiifConfig.limits().maxHeight()
              || (long) parsedSize.width() * parsedSize.height() == iiifConfig.limits().maxArea();
      if (parsedSize.equals(sourceSize)
          || (parsedSize.width() < sourceSize.width()
              && parsedSize.height() < sourceSize.height()
              && sizeIsMax)) {
        canonicalSize = isV2 ? "full" : "max";
      } else if (!isV2
          && (parsedSize.width() > sourceSize.width() || parsedSize.height() > sourceSize.height())
          && sizeIsMax) {
        canonicalSize = "^max";
      } else if (isV2 && (parsedSize.aspectRatio() == sourceSize.aspectRatio())) {
        canonicalSize = "%d,".formatted(parsedSize.width());
      } else if (!isV2 && parsedSize.width() > sourceSize.width()
          || parsedSize.height() > sourceSize.height()) {
        canonicalSize = "^%d,%d".formatted(parsedSize.width(), parsedSize.height());
      } else {
        canonicalSize = "%d,%d".formatted(parsedSize.width(), parsedSize.height());
      }
      Rotation parsedRotation = this.parseRotation(request.rotationSpec());
      String canonicalRotation =
          "%s%s"
              .formatted(
                  parsedRotation.mirror() ? "!" : "",
                  BigDecimal.valueOf(parsedRotation.degrees())
                      .stripTrailingZeros()
                      .toPlainString());
      String canonicalQuality =
          request.qualitySpec().equals(iiifConfig.qualities().defaultQuality())
              ? "default"
              : request.qualitySpec();
      return new ImageRequest(
          request.identifier(),
          request.version(),
          canonicalRegion,
          canonicalSize,
          canonicalRotation,
          canonicalQuality,
          request.formatSpec());
    } catch (IllegalArgumentException | NotImplementedException e) {
      return null;
    }
  }
}
