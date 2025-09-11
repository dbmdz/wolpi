package dev.mdz.wolpi.extension.model;

/// Supported extension languages
public enum Language {
  JAVASCRIPT("js"),
  PYTHON("python");

  private final String graalName;

  Language(String graalName) {
    this.graalName = graalName;
  }

  public String graalName() {
    return graalName;
  }
}
