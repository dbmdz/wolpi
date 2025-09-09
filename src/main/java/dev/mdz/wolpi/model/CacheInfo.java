package dev.mdz.wolpi.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record CacheInfo(@Nullable String eTag, @Nullable Instant lastModified) {}
