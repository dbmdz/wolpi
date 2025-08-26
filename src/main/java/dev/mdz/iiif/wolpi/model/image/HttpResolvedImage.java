package dev.mdz.iiif.wolpi.model.image;

import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record HttpResolvedImage(
    URI uri, @Nullable Map<String, String> headers, @Nullable Boolean supportsByteRange)
    implements ResolvedImage {}
