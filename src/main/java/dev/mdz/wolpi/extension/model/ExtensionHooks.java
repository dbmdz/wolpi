package dev.mdz.wolpi.extension.model;

/// Set of hooks that extensions can implement
public enum ExtensionHooks {
  AUTHORIZE,
  RESOLVE,
  INFO_JSON,
  PREPROCESS_IMAGE,
  SCALE,
  CROP,
  ROTATE,
  COLOR,
  FORMAT
}
