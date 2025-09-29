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
}
