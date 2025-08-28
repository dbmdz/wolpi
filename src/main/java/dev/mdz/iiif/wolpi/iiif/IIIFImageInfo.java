package dev.mdz.iiif.wolpi.iiif;

import static dev.mdz.iiif.wolpi.util.JSON.obj;

import dev.mdz.iiif.wolpi.config.IIIFConfig;
import dev.mdz.iiif.wolpi.model.IIIFComplianceLevel;
import dev.mdz.iiif.wolpi.model.image.ImageInfo;
import dev.mdz.iiif.wolpi.model.params.IIIFVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// An IIIF Image Information document, used to generate info.json responses.
public class IIIFImageInfo {

  private final ImageInfo sourceImageInfo;
  private final IIIFConfig config;
  private final IIIFComplianceLevel complianceLevelV3;
  private final List<Object> v2Profiles;
  private final List<String> v3ExtraFeatures;
  private final List<String> v3ExtraFormats;
  private final List<String> v3ExtraQualities;

  public IIIFImageInfo(ImageInfo sourceImageInfo, IIIFConfig iiifConfig) {
    this.sourceImageInfo = sourceImageInfo;
    this.config = iiifConfig;
    IIIFComplianceLevel complianceLevelV2 = getHighestFullySupportedLevel(config, IIIFVersion.V2);
    this.v2Profiles = getV2Profiles(config, complianceLevelV2);
    this.complianceLevelV3 = getHighestFullySupportedLevel(config, IIIFVersion.V3);
    this.v3ExtraFeatures = getV3ExtraFeatures(config, complianceLevelV3);
    this.v3ExtraFormats = getV3ExtraFormats(config, complianceLevelV3);
    this.v3ExtraQualities = getV3ExtraQualities(config, complianceLevelV3);
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
            .kv("profile", isV2 ? this.v2Profiles : this.complianceLevelV3.uri(IIIFVersion.V3))
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
      if (!v3ExtraFeatures.isEmpty()) {
        builder.kv("extraFeatures", v3ExtraFeatures);
      }
      if (!v3ExtraFormats.isEmpty()) {
        builder.kv("extraFormats", v3ExtraFormats);
      }
      if (!v3ExtraQualities.isEmpty()) {
        builder.kv("extraQualities", v3ExtraQualities);
      }
    }

    return builder.obj();
  }

  private static boolean isLevel2Compliant(IIIFConfig config, IIIFVersion version) {
    var regionFeats = config.features().region();
    var sizeFeats = config.features().scaling();
    var supportedQualities = config.qualities().allowed();
    var supportedFormats = config.formats().allowed();
    return isLevel1Compliant(config, version)
        && regionFeats.byPercent()
        && sizeFeats.byConfinedWidthHeight()
        && config.features().rotation().by90DegreeRotation()
        && supportedQualities.contains("color")
        && supportedQualities.contains("gray")
        && supportedFormats.contains("jpg")
        && supportedFormats.contains("png")
        && (version == IIIFVersion.V3
            ? sizeFeats.byPercent()
            : (sizeFeats.byArbitraryDimensions() && supportedQualities.contains("bitonal")));
  }

  private static boolean isLevel1Compliant(IIIFConfig config, IIIFVersion version) {
    var regionFeats = config.features().region();
    var sizeFeats = config.features().scaling();
    var supportsRequiredHttpFeatures =
        config.features().cors()
            && config.features().baseUriRedirect()
            && config.features().jsonLdMediaType();
    return supportsRequiredHttpFeatures
        && regionFeats.byPixels()
        && sizeFeats.byWidth()
        && sizeFeats.byHeight()
        && (version == IIIFVersion.V2
            ? sizeFeats.byPercent()
            : (sizeFeats.byArbitraryDimensions() && regionFeats.square()));
  }

  private static IIIFComplianceLevel getHighestFullySupportedLevel(
      IIIFConfig config, IIIFVersion version) {
    if (isLevel2Compliant(config, version)) {
      return IIIFComplianceLevel.LEVEL_2;
    } else if (isLevel1Compliant(config, version)) {
      return IIIFComplianceLevel.LEVEL_1;
    } else {
      return IIIFComplianceLevel.LEVEL_0;
    }
  }

  private static List<String> getV3ExtraFormats(IIIFConfig config, IIIFComplianceLevel level) {
    return config.formats().allowed().stream()
        .filter(f -> !f.equals("jpg") && !(level == IIIFComplianceLevel.LEVEL_2 && f.equals("png")))
        .toList();
  }

  private static List<String> getV3ExtraQualities(IIIFConfig config, IIIFComplianceLevel level) {
    return config.qualities().allowed().stream()
        .filter(q -> level != IIIFComplianceLevel.LEVEL_2 || q.equals("bitonal"))
        .toList();
  }

  private static List<String> getV3ExtraFeatures(IIIFConfig config, IIIFComplianceLevel level) {
    List<String> extraFeatures = new ArrayList<>();
    if (config.features().scaling().allowUpscaling()) {
      extraFeatures.add("sizeUpscaling");
    }
    if (config.features().rotation().mirroring()) {
      extraFeatures.add("mirroring");
    }
    if (config.features().rotation().arbitrary()) {
      extraFeatures.add("rotationArbitrary");
    }
    if (config.qualities().allowed().contains("bitonal")) {
      extraFeatures.add("bitonal");
    }
    if (config.features().profileLinkHeader()) {
      extraFeatures.add("profileLinkHeader");
    }
    if (config.features().canonicalLinkHeader()) {
      extraFeatures.add("canonicalLinkHeader");
    }
    switch (level) {
      case LEVEL_0:
        if (config.features().region().byPixels()) {
          extraFeatures.add("regionByPx");
        }
        if (config.features().region().square()) {
          extraFeatures.add("regionSquare");
        }
        if (config.features().scaling().byWidth()) {
          extraFeatures.add("sizeByW");
        }
        if (config.features().scaling().byHeight()) {
          extraFeatures.add("sizeByH");
        }
        if (config.features().scaling().byArbitraryDimensions()) {
          extraFeatures.add("sizeByWh");
        }
        if (config.features().baseUriRedirect()) {
          extraFeatures.add("baseUriRedirect");
        }
        if (config.features().cors()) {
          extraFeatures.add("cors");
        }
        if (config.features().jsonLdMediaType()) {
          extraFeatures.add("jsonldMediaType");
        }
      // No break, anything extra for LEVEL_1 is also extra for LEVEL_0
      case LEVEL_1:
        if (config.features().region().byPercent()) {
          extraFeatures.add("regionByPct");
        }
        if (config.features().scaling().byPercent()) {
          extraFeatures.add("sizeByPct");
        }
        if (config.features().scaling().byConfinedWidthHeight()) {
          extraFeatures.add("sizeByConfinedWh");
        }
        if (config.features().rotation().by90DegreeRotation()) {
          extraFeatures.add("rotationBy90s");
        }
    }
    return extraFeatures;
  }

  private static List<Object> getV2Profiles(IIIFConfig config, IIIFComplianceLevel compliance) {
    List<Object> out = new ArrayList<>();
    out.add(compliance.uri(IIIFVersion.V2));
    List<String> supports = new ArrayList<>();

    supports.add("sizeByWh");
    if (config.features().region().square()) {
      supports.add("regionSquare");
    }
    if (config.features().scaling().allowUpscaling()) {
      supports.add("sizeAboveFull");
    }
    if (config.features().rotation().mirroring()) {
      supports.add("mirroring");
    }
    if (config.features().rotation().arbitrary()) {
      supports.add("rotationArbitrary");
    }
    if (config.features().profileLinkHeader()) {
      supports.add("profileLinkHeader");
    }
    if (config.features().canonicalLinkHeader()) {
      supports.add("canonicalLinkHeader");
    }
    List<String> extraFormats = new ArrayList<>();
    config.formats().allowed().stream()
        .filter(f -> !f.equals("jpg") && !f.equals("png"))
        .forEach(extraFormats::add);
    List<String> extraQualities = new ArrayList<>();

    switch (compliance) {
      case LEVEL_0:
        if (config.features().region().byPixels()) {
          supports.add("regionByPx");
        }
        if (config.features().scaling().byWidth()) {
          supports.add("sizeByW");
        }
        if (config.features().scaling().byHeight()) {
          supports.add("sizeByH");
        }
        if (config.features().scaling().byPercent()) {
          supports.add("sizeByPct");
        }
        if (config.features().baseUriRedirect()) {
          supports.add("baseUriRedirect");
        }
        if (config.features().cors()) {
          supports.add("cors");
        }
        if (config.features().jsonLdMediaType()) {
          supports.add("jsonldMediaType");
        }
      // No break, anything extra for LEVEL_1 is also extra for LEVEL_0
      case LEVEL_1:
        if (config.features().region().byPercent()) {
          supports.add("regionByPct");
        }
        if (config.features().scaling().byConfinedWidthHeight()) {
          supports.add("sizeByConfinedWh");
        }
        if (config.features().scaling().byArbitraryDimensions()) {
          supports.add("sizeByDistortedWh");
        }
        if (config.features().rotation().by90DegreeRotation()) {
          supports.add("rotationBy90s");
        }
        if (config.formats().allowed().contains("png")) {
          extraFormats.add("png");
        }
        config.qualities().allowed().stream()
            .filter(q -> !q.equals("color"))
            .forEach(extraQualities::add);
        break;
    }

    Map<String, Object> extraProfile = new HashMap<>();
    if (!extraFormats.isEmpty()) {
      extraProfile.put("formats", extraFormats);
    }
    if (config.limits().maxArea() > 0) {
      extraProfile.put("maxArea", config.limits().maxArea());
    }
    if (config.limits().maxWidth() > 0) {
      extraProfile.put("maxWidth", config.limits().maxWidth());
    }
    if (config.limits().maxHeight() > 0) {
      extraProfile.put("maxHeight", config.limits().maxHeight());
    }
    if (!extraQualities.isEmpty()) {
      extraProfile.put("qualities", extraQualities);
    }
    if (!supports.isEmpty()) {
      extraProfile.put("supports", supports);
    }
    if (!extraProfile.isEmpty()) {
      out.add(extraProfile);
    }
    return out;
  }
}
