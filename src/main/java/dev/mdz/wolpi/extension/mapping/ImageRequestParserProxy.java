package dev.mdz.wolpi.extension.mapping;

import dev.mdz.wolpi.extension.model.Language;
import dev.mdz.wolpi.iiif.ImageRequestParser;
import dev.mdz.wolpi.iiif.exceptions.NotImplementedException;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.iiif.model.ImageRequest;
import dev.mdz.wolpi.model.ImageSize;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jspecify.annotations.Nullable;

/// Wrapper around [ImageRequestParser] that maps returned objects to Polyglot [ProxyObject]s.
///
public class ImageRequestParserProxy {
    private final ImageRequestParser imageRequestParser;
    private final Language guestLanguage;

    public ImageRequestParserProxy(ImageRequestParser imageRequestParser, Language guestLanguage) {
        this.imageRequestParser = imageRequestParser;
        this.guestLanguage = guestLanguage;
    }

    public String parseQuality(String qualitySpec) {
        return switch (imageRequestParser.parseQuality(qualitySpec)) {
            case COLOR -> "color";
            case GRAY -> "gray";
            case BITONAL -> "bitonal";
        };
    }

    public ProxyObject parseRegion(String cropSpec, ImageSize sourceSize) {
        return new RecordProxy(imageRequestParser.parseRegion(cropSpec, sourceSize), guestLanguage);
    }

    public ProxyObject parseRotation(String rotationSpec) {
        return new RecordProxy(imageRequestParser.parseRotation(rotationSpec), guestLanguage);
    }

    public ProxyObject parseSize(IIIFVersion iiifVersion, String sizeSpec, ImageSize sourceSize)
            throws NotImplementedException {
        return new RecordProxy(imageRequestParser.parseSize(iiifVersion, sizeSpec, sourceSize), guestLanguage);
    }

    @Nullable public ProxyObject toCanonicalForm(ImageRequest request, ImageSize sourceSize) {
        var canonicalForm = imageRequestParser.toCanonicalForm(request, sourceSize);
        if (canonicalForm == null) {
            return null;
        }
        return new RecordProxy(canonicalForm, guestLanguage);
    }
}
