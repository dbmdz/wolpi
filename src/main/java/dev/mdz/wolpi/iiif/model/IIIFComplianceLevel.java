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

  public URI uri(IIIFVersion version) {
    return URI.create(
        "http://iiif.io/api/image/%d/level%d.json"
            .formatted(version == IIIFVersion.V2 ? 2 : 3, value));
  }
}
