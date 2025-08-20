package dev.mdz.iiif.wolpi.model.image;

/// An image that was resolved to a binary blob with a specific MIME type.
public record BinaryResolvedImage(byte[] rawData, String mimeType) implements ResolvedImage {}
