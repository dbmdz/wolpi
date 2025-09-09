package dev.mdz.wolpi.model;

import java.nio.ByteBuffer;

/// The encoded image data and its content type.
public record EncodedImage(ByteBuffer data, String contentType) {}
