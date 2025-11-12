package dev.mdz.wolpi.iiif;

import static dev.mdz.wolpi.iiif.util.JSON.obj;

import dev.mdz.wolpi.config.IIIFConfig;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.model.ImageInfo;
import java.util.Map;

/// An IIIF Image Information document, used to generate info.json responses.
public class IIIFImageInfo {

    private final ImageInfo sourceImageInfo;
    private final IIIFConfig config;
    private final IIIFComplianceRegistry complianceRegistry;

    public IIIFImageInfo(ImageInfo sourceImageInfo, IIIFConfig iiifConfig, IIIFComplianceRegistry complianceRegistry) {
        this.sourceImageInfo = sourceImageInfo;
        this.config = iiifConfig;
        this.complianceRegistry = complianceRegistry;
    }

    /// Create a JSON representation of the IIIF Image Information, compliant with the given IIIF
    /// Image API Version.
    ///
    /// @param version IIIF Image API Version to generate the representation for
    /// @param baseUrl Base URL for the image service of the image (i.e. without /info.json suffix),
    ///                used as the identifier
    public Map<String, Object> toJSON(IIIFVersion version, String baseUrl) {
        boolean isV2 = version == IIIFVersion.V2;
        var builder = obj().kv("@context", "http://iiif.io/api/image/%d/context.json".formatted(isV2 ? 2 : 3))
                .kv(isV2 ? "@id" : "id", baseUrl)
                .kv(isV2 ? "@type" : "type", isV2 ? "iiif:Image" : "ImageService3")
                .kv("protocol", "http://iiif.io/api/image")
                .kv(
                        "profile",
                        isV2
                                ? complianceRegistry.v2Profiles()
                                : complianceRegistry.complianceLevelV3().v3String())
                .kv("width", sourceImageInfo.nativeSize().width())
                .kv("height", sourceImageInfo.nativeSize().height());

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
                            .map(size -> obj().kv(isV2 ? "@type" : "type", isV2 ? "iiif:Size" : "Size")
                                    .kv("width", size.width())
                                    .kv("height", size.height())
                                    .obj())
                            .toList());
        }

        if (!sourceImageInfo.tileSizes().isEmpty()) {
            builder.kv(
                    "tiles",
                    sourceImageInfo.tileSizes().stream()
                            .map(tile -> obj().kv(isV2 ? "@type" : "type", isV2 ? "iiif:Tile" : "Tile")
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
            if (!complianceRegistry.v3ExtraFeatures().isEmpty()) {
                builder.kv("extraFeatures", complianceRegistry.v3ExtraFeatures());
            }
            if (!complianceRegistry.v3ExtraFormats().isEmpty()) {
                builder.kv("extraFormats", complianceRegistry.v3ExtraFormats());
            }
            if (!complianceRegistry.v3ExtraQualities().isEmpty()) {
                builder.kv("extraQualities", complianceRegistry.v3ExtraQualities());
            }
        }

        return builder.obj();
    }
}
