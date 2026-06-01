package dev.mdz.wolpi.extension.mapping;

import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import dev.mdz.wolpi.model.BinaryResolvedImage;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.ResolvedImage;
import dev.mdz.wolpi.model.SourceNotModified;
import java.util.Set;
import org.graalvm.polyglot.Value;

public class ResolvedImageMapper {
    private static final Set<String> RESOLVED_IMAGE_MEMBERS = Set.of("url", "rawData", "path", "notModified");

    private ResolvedImageMapper() {}

    public static boolean canMap(Value val) {
        return RESOLVED_IMAGE_MEMBERS.stream().anyMatch(m -> PolyglotHelpers.hasDictOrObjectMember(m, val, true));
    }

    public static ResolvedImage map(Value val) {
        Value notModifiedValue = PolyglotHelpers.getDictOrObjectMember("notModified", val, true);
        if (notModifiedValue != null && !notModifiedValue.isNull() && notModifiedValue.asBoolean()) {
            return new SourceNotModified(true);
        }

        if (PolyglotHelpers.hasDictOrObjectMember("path", val)) {
            return val.as(FilesystemResolvedImage.class);
        } else if (PolyglotHelpers.hasDictOrObjectMember("rawData", val, true)) {
            return val.as(BinaryResolvedImage.class);
        } else if (PolyglotHelpers.hasDictOrObjectMember("url", val)) {
            return val.as(HttpResolvedImage.class);
        } else {
            throw new IllegalArgumentException(
                    "Cannot map polyglot value [%s] to ResolvedImage".formatted(val.toString()));
        }
    }
}
