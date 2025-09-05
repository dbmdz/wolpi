package dev.mdz.iiif.wolpi.model.image;

public sealed interface ResolvedImage
    permits FilesystemResolvedImage,
        HttpResolvedImage,
        BinaryResolvedImage,
        CustomSourceResolvedImage {}
