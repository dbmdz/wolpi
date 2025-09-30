package dev.mdz.wolpi.iiif.model;

import java.net.URI;

public enum IIIFComplianceLevel {
    LEVEL_0(0),
    LEVEL_1(1),
    LEVEL_2(2);

    private final int value;

    IIIFComplianceLevel(int value) {
        this.value = value;
    }

    public URI v2Uri() {
        return URI.create("http://iiif.io/api/image/2/level%d.json".formatted(value));
    }

    public String v3String() {
        return "level%d".formatted(value);
    }
}
