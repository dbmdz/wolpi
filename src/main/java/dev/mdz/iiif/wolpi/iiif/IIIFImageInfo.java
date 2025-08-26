package dev.mdz.iiif.wolpi.iiif;

import static dev.mdz.iiif.wolpi.util.JSON.obj;

import dev.mdz.iiif.wolpi.config.IIIFConfig;
import dev.mdz.iiif.wolpi.model.image.ImageInfo;
import dev.mdz.iiif.wolpi.model.params.IIIFVersion;
import java.util.List;
import java.util.Map;

/// An IIIF Image Information document, used to generate info.json responses.
public class IIIFImageInfo {

  private final ImageInfo sourceImageInfo;
  private final IIIFConfig config;

  public IIIFImageInfo(ImageInfo sourceImageInfo, IIIFConfig iiifConfig) {
    this.sourceImageInfo = sourceImageInfo;
    this.config = iiifConfig;
  }

  /// Create a JSON representation of the IIIF Image Information, compliant with the given IIIF
  /// Image API Version.
  ///
  /// @param version IIIF Image API Version to generate the representation for
  /// @param baseUrl Base URL for the image service of the image (i.e. without /info.json suffix),
  ///                used as the identifier
  public Map<String, Object> toJSON(IIIFVersion version, String baseUrl) {
    boolean isV2 = version == IIIFVersion.V2;
    var builder =
        obj()
            .kv("@context", "http://iiif.io/api/image/%d/context.json".formatted(isV2 ? 2 : 3))
            .kv(isV2 ? "@id" : "id", baseUrl)
            .kv(isV2 ? "@type" : "type", isV2 ? "iiif:Image" : "ImageService3")
            .kv("protocol", "http://iiif.io/api/image")
            .kv("profile", isV2 ? getV2Profiles() : getHighestFullySupportedV3Profile())
            .kv("width", sourceImageInfo.nativeWidth())
            .kv("height", sourceImageInfo.nativeHeight());

    if (!isV2) {
      if (config.limits().maxWidth() > 0) {
        builder.kv("maxWidth", config.limits().maxWidth());
        if (config.limits().maxHeight() > 0) {
          builder.kv("maxHeight", config.limits().maxHeight());
        }
      }

      if (config.limits().maxArea() > 0) {
        builder.kv("maxArea", config.limits().maxArea());
      }
    }

    if (!sourceImageInfo.sizes().isEmpty()) {
      builder.kv(
          "sizes",
          sourceImageInfo.sizes().stream()
              .map(
                  size ->
                      obj()
                          .kv(isV2 ? "@type" : "type", isV2 ? "iiif:Size" : "Size")
                          .kv("width", size.width())
                          .kv("height", size.height())
                          .obj())
              .toList());
    }

    if (!sourceImageInfo.getTileSizes().isEmpty()) {
      builder.kv(
          "tiles",
          sourceImageInfo.getTileSizes().stream()
              .map(
                  tile ->
                      obj()
                          .kv(isV2 ? "@type" : "type", isV2 ? "iiif:Tile" : "Tile")
                          .kv("width", tile.width())
                          .kv("height", tile.height() == null ? null : tile.height())
                          .kv("scaleFactors", tile.scaleFactors())
                          .obj())
              .toList());
    }

    if (!isV2 && !config.formats().preferred().isEmpty()) {
      builder.kv("preferredFormats", config.formats().preferred());
    }

    if (!isV2) {
      var extraFeatures = getExtraFeatures(version, getHighestFullySupportedV3Profile());
      if (!extraFeatures.isEmpty()) {
        builder.kv("extraFeatures", extraFeatures);
      }
    }

    return builder.obj();
  }

  private String getHighestFullySupportedV3Profile() {
    // TODO: Based on the config, determine the highest fully supported profile
    return "level2";
  }

  private List<String> getV2Profiles() {
    // TODO: Based on the config, determine the highest fully supported profile
    return List.of("http://iiif.io/api/image/2/level2.json");
  }

  private List<String> getExtraFeatures(IIIFVersion version, String profile) {
    // TODO: Determine extra features (i.e. that are supported in addition to the ones supported by
    // the profile)
    return List.of();
  }
}
