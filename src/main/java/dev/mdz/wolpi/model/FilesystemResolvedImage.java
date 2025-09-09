package dev.mdz.wolpi.model;

import java.nio.file.Path;

public record FilesystemResolvedImage(Path path) implements ResolvedImage {}
