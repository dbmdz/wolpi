package dev.mdz.wolpi.model;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;

/// The encoded image data and its content type, optionally with extra http headers for the response.
public record EncodedImage(ByteBuffer data, String contentType, @Nullable Map<String, List<String>> extraHeaders) {}
