package dev.mdz.wolpi.extension.model;

import java.util.Arrays;
import java.util.HashSet;
import org.jspecify.annotations.Nullable;

/// Set of hooks that extensions can implement
public enum ExtensionHooks {
  AUTHORIZE("authorize"),
  RESOLVE("resolve"),
  INFO_JSON("process_infojson_v2", "process_infojson_v3", "processInfojsonV2", "processInfojsonV3"),
  PREPROCESS_IMAGE("pre_process_image", "preProcessImage"),
  SCALE("pre_scale", "preScale"),
  CROP("pre_crop", "preCrop"),
  ROTATE("pre_rotate", "preRotate"),
  COLOR("pre_color", "preColor"),
  FORMAT("pre_format", "preFormat");

  private final HashSet<String> validNames;

  ExtensionHooks(String... validNames) {
    this.validNames = new HashSet<>(Arrays.asList(validNames));
  }

  public static @Nullable ExtensionHooks fromName(String name) {
    return Arrays.stream(values())
        .filter(hook -> hook.validNames.contains(name))
        .findFirst()
        .orElse(null);
  }
}
