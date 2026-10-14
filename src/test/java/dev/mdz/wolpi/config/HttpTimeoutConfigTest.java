package dev.mdz.wolpi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class HttpTimeoutConfigTest {
    @Test
    void defaultsApplyToBothClients() {
        var config = bind(Map.of());
        assertThat(config).isEqualTo(HttpTimeoutConfig.defaults());
        assertThat(config.forExtensions()).isEqualTo(config.forSources());
        assertThat(config.forSources().connect()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.forSources().request()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void overridesInheritOmittedValues() {
        var config = bind(Map.of(
                "wolpi.http-timeouts.connect",
                "250ms",
                "wolpi.http-timeouts.request",
                "750ms",
                "wolpi.http-timeouts.sources.request",
                "2m",
                "wolpi.http-timeouts.extensions.connect",
                "100ms"));
        assertThat(config.forSources().connect()).isEqualTo(Duration.ofMillis(250));
        assertThat(config.forSources().request()).isEqualTo(Duration.ofMinutes(2));
        assertThat(config.forExtensions().connect()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.forExtensions().request()).isEqualTo(Duration.ofMillis(750));
    }

    @Test
    void invalidDefaultsAndOverridesAreRejected() {
        assertThatThrownBy(() -> bind(Map.of("wolpi.http-timeouts.request", "0s")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> bind(Map.of("wolpi.http-timeouts.sources.connect", "-1s")))
                .isInstanceOf(RuntimeException.class);
    }

    private HttpTimeoutConfig bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("wolpi.http-timeouts", HttpTimeoutConfig.class);
    }
}
