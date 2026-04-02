package dev.mdz.wolpi.extension.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Set of hooks that extensions can implement
public enum ExtensionHooks {
    INFO("info"),
    SETUP("setup"),
    DESTROY("destroy"),
    CLEANUP("cleanup"),
    SKIPPABLE_HOOKS("skippable_hooks", "skippableHooks"),
    AUTHORIZE("authorize"),
    RESOLVE("resolve"),
    INFO_JSON("augment_info_json", "augmentInfoJson"),
    PREPROCESS_IMAGE("pre_process_image", "preProcessImage"),
    SCALE("pre_scale", "preScale"),
    CROP("pre_crop", "preCrop"),
    ROTATE("pre_rotate", "preRotate"),
    QUALITY("pre_quality", "preQuality"),
    FORMAT("pre_format", "preFormat");

    private final HashSet<String> validNames;

    ExtensionHooks(String... validNames) {
        this.validNames = new HashSet<>(Arrays.asList(validNames));
    }

    public Set<String> getValidNames() {
        return validNames;
    }

    public static @Nullable ExtensionHooks fromName(String name) {
        return Arrays.stream(values())
                .filter(hook -> hook.validNames.contains(name))
                .findFirst()
                .orElse(null);
    }
}
