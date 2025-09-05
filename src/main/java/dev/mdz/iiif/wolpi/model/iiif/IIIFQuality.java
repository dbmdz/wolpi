package dev.mdz.iiif.wolpi.model.iiif;

public enum IIIFQuality {
  COLOR,
  GRAY,
  BITONAL;

  public static IIIFQuality fromString(String quality) {
    return switch (quality.toLowerCase()) {
      case "color" -> COLOR;
      case "gray" -> GRAY;
      case "bitonal" -> BITONAL;
      default -> throw new IllegalStateException("Unexpected value: " + quality.toLowerCase());
    };
  }
}
