package dev.mdz.iiif.wolpi.model.image;

import java.nio.file.Path;

public record FilesystemResolvedImage(Path path) implements ResolvedImage {}
