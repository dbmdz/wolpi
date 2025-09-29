package dev.mdz.wolpi.iiif.model;

import java.net.URI;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

public enum IIIFComplianceLevel {
    LEVEL_0(0),
    LEVEL_1(1),
    LEVEL_2(2),
    OPTIONAL(3);

    private final int value;

    public static @Nullable IIIFComplianceLevel fromInt(int value) {
        return Arrays.stream(IIIFComplianceLevel.values())
            .filter(level -> level.value == value)
            .findFirst()
            .orElse(null);
    }

    IIIFComplianceLevel(int value) {
        this.value = value;
    }

    public URI v2Uri() {
        if (this == OPTIONAL) {
            throw new IllegalArgumentException("Optional level does not have a v2 URI");
        }
        return URI.create("http://iiif.io/api/image/2/level%d.json".formatted(value));
    }

    public String v3String() {
        if (this == OPTIONAL) {
            throw new IllegalArgumentException("Optional level does not have a v3 string");
        }
        return "level%d".formatted(value);
    }
}
