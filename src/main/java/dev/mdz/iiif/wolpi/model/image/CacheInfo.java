package dev.mdz.iiif.wolpi.model.image;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record CacheInfo(@Nullable String eTag, @Nullable Instant lastModified) {}
