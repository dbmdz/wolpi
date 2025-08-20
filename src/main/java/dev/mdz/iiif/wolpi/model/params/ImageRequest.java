package dev.mdz.iiif.wolpi.model.params;

public record ImageRequest(
    String identifier,
    IIIFVersion version,
    String cropSpec,
    String sizeSpec,
    String rotationSpec,
    String qualitySpec,
    String formatSpec) {}
