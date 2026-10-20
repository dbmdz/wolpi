package dev.mdz.wolpi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class HttpSourceCacheConfigTest {
    @Test
    void defaultsEnableDiskBackedCaching() {
        var config = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("wolpi.http-source-cache", HttpSourceCacheConfig.class);
        assertThat(config).isEqualTo(HttpSourceCacheConfig.defaults());
    }

    @Test
    void bindsFlatSizeFreshnessDirectoryAndHeaderSwitch() {
        var properties = new MapConfigurationPropertySource(Map.of(
                "wolpi.http-source-cache.max-resource-size", "20MB",
                "wolpi.http-source-cache.expose-response-headers", "false",
                "wolpi.http-source-cache.directory", "/var/cache/wolpi",
                "wolpi.http-source-cache.max-size", "2GB",
                "wolpi.http-source-cache.max-freshness", "5m"));
        var config = new Binder(properties).bindOrCreate("wolpi.http-source-cache", HttpSourceCacheConfig.class);
        assertThat(config.enabled()).isTrue();
        assertThat(config.exposeResponseHeaders()).isFalse();
        assertThat(config.maxResourceSize()).isEqualTo(DataSize.ofMegabytes(20));
        assertThat(config.directory()).isEqualTo(Path.of("/var/cache/wolpi"));
        assertThat(config.maxSize()).isEqualTo(DataSize.ofGigabytes(2));
        assertThat(config.maxFreshness()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsInvalidSizesAndFreshness() {
        var defaults = HttpSourceCacheConfig.defaults();
        assertThatThrownBy(() -> new HttpSourceCacheConfig(
                        true, null, DataSize.ofBytes(0), defaults.maxFreshness(), defaults.maxResourceSize(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpSourceCacheConfig(
                        true, null, defaults.maxSize(), Duration.ZERO, defaults.maxResourceSize(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpSourceCacheConfig(
                        true, null, defaults.maxSize(), defaults.maxFreshness(), DataSize.ofBytes(-1), true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
