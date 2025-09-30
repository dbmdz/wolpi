package dev.mdz.wolpi.iiif;

import dev.mdz.wolpi.config.IIIFConfig;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.iiif.model.IIIFComplianceLevel;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/// Has the IIIF compliance levels and profiles that Wolpi supports, based on the current
/// configuration, along with any extra features, formats and qualities that are supported beyond
// the
/// compliance level.
@Component
public class IIIFComplianceRegistry {
    private final IIIFComplianceLevel complianceLevelV3;
    private final List<Object> v2Profiles;
    private final List<String> v3ExtraFeatures;
    private final List<String> v3ExtraFormats;
    private final List<String> v3ExtraQualities;

    public IIIFComplianceRegistry(WolpiConfig wolpiConfig) {
        IIIFConfig config = wolpiConfig.iiif();
        IIIFComplianceLevel complianceLevelV2 = getHighestFullySupportedLevel(config, IIIFVersion.V2);
        this.v2Profiles = getV2Profiles(config, complianceLevelV2);
        this.complianceLevelV3 = getHighestFullySupportedLevel(config, IIIFVersion.V3);
        this.v3ExtraFeatures = getV3ExtraFeatures(config, complianceLevelV3);
        this.v3ExtraFormats = getV3ExtraFormats(config, complianceLevelV3);
        this.v3ExtraQualities = getV3ExtraQualities(config, complianceLevelV3);
    }

    /// Get the highest fully supported IIIF Image API 3.0 compliance level for the current
    /// configuration.
    public IIIFComplianceLevel complianceLevelV3() {
        return complianceLevelV3;
    }

    /// Get the full set of IIIF Image API 2.0 profiles supported by the current configuration, in
    /// a form that can be directly embedded into the info.json response.
    ///
    /// @return List of profiles, each either a string URI or an object with additional properties
    public List<Object> v2Profiles() {
        return v2Profiles;
    }

    /// Get the list of extra IIIF Image API 3.0 features supported by the current configuration,
    /// beyond those required for the compliance level returned by [#complianceLevelV3()].
    public List<String> v3ExtraFeatures() {
        return v3ExtraFeatures;
    }

    /// Get the list of extra IIIF Image API 3.0 formats supported by the current configuration,
    // beyond
    /// those required for the compliance level returned by [#complianceLevelV3()]
    public List<String> v3ExtraFormats() {
        return v3ExtraFormats;
    }

    /// Get the list of extra IIIF Image API 3.0 qualities supported by the current configuration,
    /// beyond those required for the compliance level returned by [#complianceLevelV3()]
    public List<String> v3ExtraQualities() {
        return v3ExtraQualities;
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
        var supportsRequiredHttpFeatures = config.features().cors()
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

    private static IIIFComplianceLevel getHighestFullySupportedLevel(IIIFConfig config, IIIFVersion version) {
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
        out.add(compliance.v2Uri());
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
