package dev.mdz.wolpi.extension;

import static com.google.common.base.Throwables.getCausalChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.mdz.wolpi.Wolpi;
import dev.mdz.wolpi.extension.exceptions.ExtensionInitializationException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/// Verifies that Wolpi refuses to start when a configured extension cannot be loaded.
///
/// Booting with an authorization extension that fails to load used to leave the server running
/// without the hook, i.e. it silently served every request the extension was meant to protect.
@DisplayName("Extension load failures at startup")
class ExtensionStartupFailureTest {
    private static final Path BROKEN_EXTENSION = Path.of("src/test/resources/broken-authorize.js");

    @Test
    @DisplayName("should prevent the application from starting")
    void shouldPreventApplicationStartup() {
        var app = new SpringApplicationBuilder(Wolpi.class).profiles("test").build();
        var thrown = catchThrowable(
                () -> app.run("--server.port=0", "--wolpi.extensions[0].path=" + BROKEN_EXTENSION.toAbsolutePath()));

        assertThat(thrown).isNotNull();
        assertThat(getCausalChain(thrown)).anySatisfy(cause -> assertThat(cause)
                .isInstanceOf(ExtensionInitializationException.class)
                .hasMessageContaining("Failed to load 1 of 1 configured extension(s)")
                .hasMessageContaining("broken-authorize.js")
                .hasMessageContaining("boom during extension initialization"));
    }
}
