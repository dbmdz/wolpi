package dev.mdz.wolpi.config;

import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/// Disk-backed whole-response caching for HTTP image sources. Maximum freshness caps reuse, not retention:
/// expired bodies remain available for conditional revalidation until size-based eviction.
public record HttpSourceCacheConfig(
        @DefaultValue("true") boolean enabled,
        @Nullable Path directory,
        @DefaultValue("10GB") DataSize maxSize,
        @DefaultValue("30s") Duration maxFreshness,
        @DefaultValue("512MB") DataSize maxResourceSize,
        @DefaultValue("true") boolean exposeResponseHeaders) {
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
    }

    public static HttpSourceCacheConfig defaults() {
        return new HttpSourceCacheConfig(
                true, null, DataSize.ofGigabytes(10), Duration.ofSeconds(30), DataSize.ofMegabytes(512), true);
    }
}
