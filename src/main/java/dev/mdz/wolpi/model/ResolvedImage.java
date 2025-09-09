package dev.mdz.wolpi.model;

public sealed interface ResolvedImage
    permits FilesystemResolvedImage,
        HttpResolvedImage,
        BinaryResolvedImage,
        CustomSourceResolvedImage {}
