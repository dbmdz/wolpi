package dev.mdz.wolpi.extension.util;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mdz.wolpi.testutil.ProcessBuilderMocks;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CommandRunner")
class CommandRunnerTest {

    @Test
    @DisplayName("should redact URL credentials in error messages")
    void redactsCredentialsInErrorMessages() {
        var builder =
                ProcessBuilderMocks.builder().failure().stderr("could not reach https://user:secret@example.com/");
        try (var _ = builder.build()) {
            assertThatThrownBy(() -> CommandRunner.runCommand(
                            Path.of("pip"),
                            null,
                            Duration.ofSeconds(1),
                            "install",
                            "--index-url",
                            "https://user:secret@example.com/simple"))
                    .hasMessageContaining("://***@example.com")
                    .hasMessageNotContaining("secret");
        }
    }
}
