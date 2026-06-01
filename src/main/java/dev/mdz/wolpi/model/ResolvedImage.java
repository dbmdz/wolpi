package dev.mdz.wolpi.model;

public sealed interface ResolvedImage
        permits BinaryResolvedImage, FilesystemResolvedImage, HttpResolvedImage, SourceNotModified {}
