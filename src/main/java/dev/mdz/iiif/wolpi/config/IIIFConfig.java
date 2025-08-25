package dev.mdz.iiif.wolpi.config;

import java.util.List;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/// @param restrictToSizes Don't allow arbitrary sizes for scaling, restrict to the pre-existing
///                        sizes available in the input image.
/// @param limits          Limits for the image processing, such as maximum width, height, and area.
/// @param features        Features that can be enabled or disabled, such as upscaling, CORS, and
///                        link headers.
/// @param qualities       The qualities that can be requested, such as default quality and allowed
///                        qualities.
/// @param formats         The formats that can be requested, including allowed formats and
///                        preferred formats.
public record IIIFConfig(
    boolean restrictToSizes,
    Limits limits,
    Features features,
    Qualities qualities,
    Formats formats) {

  /// @param maxWidth  Maximum width that images can be returned in
  /// @param maxHeight Maximum height that images can be returned in
  /// @param maxArea   Maximum area `(width * height)` that images can be returned in.
  public record Limits(int maxWidth, int maxHeight, long maxArea) {}

  /// Optional IIIF Image API features that can be toggled.
  ///
  /// @param scaling             Flags to control allowed scaling features
  /// @param region              Flags to control allowed region features
  /// @param rotation            Flags to control allowed rotation features
  /// @param profileLinkHeader   Whether to include the profile link header in responses
  /// @param jsonLdMediaType     Whether to use JSON-LD media type for responses
  /// @param cors                Whether to set the CORS header to `*`
  /// @param canonicalLinkHeader Whether to include a canonical link header in responses
  /// @param canonicalRedirect   Whether to redirect to the canonical URL for the image
  /// @param baseUriRedirect     Whether to the info.json endpoint when accessing the base URI
  ///                            without image parameters or `/info.json` suffix.
  public record Features(
      @NestedConfigurationProperty ScalingFeature scaling,
      @NestedConfigurationProperty RegionFeature region,
      @NestedConfigurationProperty RotationFeature rotation,
      boolean profileLinkHeader,
      boolean jsonLdMediaType,
      boolean cors,
      boolean canonicalLinkHeader,
      boolean canonicalRedirect,
      boolean baseUriRedirect) {}

  /// Optional scaling features that can be enabled or disabled
  ///
  /// @param byConfinedWidthHeight Whether to allow scaling by confined width and height with `!w,h`
  /// @param byHeight              Whether to allow scaling by height with `,h`
  /// @param byWidth               Whether to allow scaling by width with `w,`
  /// @param byPercent             Whether to allow scaling by percent with `pct:`
  /// @param byArbitraryDimensions Whether to allow scaling by arbitrary (non-aspect-ratio
  ///                              preserving) dimensions with `w,h`
  /// @param allowUpscaling        Allow upscaling of images, i.e. scaling to larger sizes than the
  ///                              original
  public record ScalingFeature(
      boolean byConfinedWidthHeight,
      boolean byHeight,
      boolean byWidth,
      boolean byPercent,
      boolean byArbitraryDimensions,
      boolean allowUpscaling) {}

  /// Optional region features that can be enabled or disabled
  ///
  /// @param byPercent Whether to allow regions specified by percent with `pct:x,y,w,h`
  /// @param byPixels  Whether to allow regions specified by pixels with `x,y,w,h`
  /// @param square    Whether to allow square regions with `square`
  public record RegionFeature(boolean byPercent, boolean byPixels, boolean square) {}

  /// Optional rotation features that can be enabled or disabled
  ///
  /// @param mirroring          Whether to allow mirroring of images on the vertical axis with `!`
  /// @param by90DegreeRotation Whether to allow 90 degree rotations with `0,90,180,270`
  /// @param arbitrary          Whether to allow arbitrary rotations with `arbitrary:angle`
  public record RotationFeature(boolean mirroring, boolean by90DegreeRotation, boolean arbitrary) {}

  /// Allowed and default "qualities" for images.
  ///
  /// See [IIIF Image API 3.0 Quality](https://iiif.io/api/image/3.0/#quality) for more details.
  ///
  /// **Note:** This is explicitly not restricted to certain values with an enum,
  /// since we want to allow custom qualities to be defined by extensions.
  ///
  /// @param defaultQuality The quality to use if `default` is requested.
  /// @param allowed        List of allowed qualities that can be requested.
  public record Qualities(String defaultQuality, List<String> allowed) {}

  /// Allowed and preferred formats for images.
  ///
  /// **Note:** This is explicitly not restricted to certain values with an enum,
  /// since we want to allow custom formats to be defined by extensions.
  ///
  /// @param allowed   List of allowed formats that can be requested, as file extensions without the
  ///                  leading dot.
  /// @param preferred List of preferred formats that should be requested by clients, as file
  ///                  extensions without the leading dot.
  public record Formats(List<String> allowed, List<String> preferred) {}
}
