package dev.mdz.wolpi.iiif.model;

import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

public record ImageRequest(
        String identifier,
        IIIFVersion version,
        String cropSpec,
        String sizeSpec,
        String rotationSpec,
        String qualitySpec,
        String formatSpec) {

    // Create a full image request for the given identifier and IIIF version
    public static ImageRequest full(String identifier, IIIFVersion version) {
        return new ImageRequest(
                identifier, version, "full", version == IIIFVersion.V2 ? "full" : "max", "0", "default", "jpg");
    }

    ///  Construct the IIIF Image API request path for this request
    public String toRequestPath() {
        return "/%s/%s/%s/%s/%s/%s.%s"
                .formatted(
                        version.name().toLowerCase(),
                        encodePathSegment(identifier),
                        encodePathSegment(cropSpec),
                        encodePathSegment(sizeSpec),
                        encodePathSegment(rotationSpec),
                        encodePathSegment(qualitySpec),
                        encodePathSegment(formatSpec));
    }

    public static String encodePathSegment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }
}
