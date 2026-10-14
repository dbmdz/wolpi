package dev.mdz.wolpi.config;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/// Shared HTTP limits. Per-client overrides inherit omitted values from the shared defaults.
public record HttpTimeoutConfig(
        @DefaultValue("10s") Duration connect,
        @DefaultValue("30s") Duration request,
        @NestedConfigurationProperty @Nullable Overrides extensions,
        @NestedConfigurationProperty @Nullable Overrides sources) {
    public HttpTimeoutConfig {
        positive(connect);
        positive(request);
    }

    public static HttpTimeoutConfig defaults() {
        return new HttpTimeoutConfig(Duration.ofSeconds(10), Duration.ofSeconds(30), null, null);
    }

    public Limits forExtensions() {
        return resolve(extensions);
    }

    public Limits forSources() {
        return resolve(sources);
    }

    private Limits resolve(@Nullable Overrides overrides) {
        return new Limits(
                overrides == null || overrides.connect() == null ? connect : overrides.connect(),
                overrides == null || overrides.request() == null ? request : overrides.request());
    }

    private static void positive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
    }

    public record Overrides(
            @Nullable Duration connect, @Nullable Duration request) {
        public Overrides {
            if (connect != null) positive(connect);
            if (request != null) positive(request);
        }
    }

    /// Stock JDK connect and request timeouts. On Java 25, request timeouts stop at response headers.
    public record Limits(Duration connect, Duration request) {
        public Limits {
            positive(connect);
            positive(request);
        }
    }
}
