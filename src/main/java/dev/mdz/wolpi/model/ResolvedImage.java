package dev.mdz.wolpi.model;

public sealed interface ResolvedImage
    permits BinaryResolvedImage,
        CustomSourceResolvedImage,
        FilesystemResolvedImage,
        HttpResolvedImage,
        SourceNotModified {}
