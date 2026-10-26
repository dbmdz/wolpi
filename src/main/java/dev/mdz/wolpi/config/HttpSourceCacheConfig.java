package dev.mdz.wolpi.config;

import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/// Disk cache for complete HTTP image bodies. Check the origin after maxFreshness; staleIfError
/// allows an expired body if that check fails. Size limits decide when to evict stored files.
public record HttpSourceCacheConfig(
        @DefaultValue("true") boolean enabled,
        @Nullable Path directory,
        @DefaultValue("10GB") DataSize maxSize,
        @DefaultValue("1h") Duration maxFreshness,
        @DefaultValue("128MB") DataSize maxResourceSize,
        @DefaultValue("true") boolean exposeResponseHeaders,
        @DefaultValue("7d") Duration staleIfError) {
    public HttpSourceCacheConfig {
        if (maxSize == null
                || maxSize.toBytes() <= 0
                || maxFreshness == null
                || maxFreshness.isNegative()
                || maxFreshness.isZero()) {
            throw new IllegalArgumentException("HTTP source cache size and max-freshness must be positive");
        }
        if (maxResourceSize == null || maxResourceSize.toBytes() <= 0) {
            throw new IllegalArgumentException("HTTP source cache max-resource-size must be positive");
        }
        if (staleIfError == null || staleIfError.isNegative()) {
            throw new IllegalArgumentException("HTTP source cache stale-if-error must not be negative");
        }
    }

    public static HttpSourceCacheConfig defaults() {
        return new HttpSourceCacheConfig(
                true,
                null,
                DataSize.ofGigabytes(10),
                Duration.ofHours(1),
                DataSize.ofMegabytes(128),
                true,
                Duration.ofDays(7));
    }
}
