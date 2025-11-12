package dev.mdz.wolpi.iiif.model;

public enum IIIFVersion {
    V2(2),
    V3(3);

    private final int value;

    IIIFVersion(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static IIIFVersion fromString(String version) {
        return switch (version.toLowerCase()) {
            case "v2" -> V2;
            case "v3" -> V3;
            default -> throw new IllegalArgumentException("Unsupported IIIF version: " + version);
        };
    }
}
