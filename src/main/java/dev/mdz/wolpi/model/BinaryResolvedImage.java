package dev.mdz.wolpi.model;

/// An image that was resolved to a binary blob with a specific MIME type.
public record BinaryResolvedImage(byte[] rawData) implements ResolvedImage {}
