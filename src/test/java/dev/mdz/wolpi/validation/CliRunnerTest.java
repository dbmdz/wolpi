package dev.mdz.wolpi.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.NpmInstaller;
import dev.mdz.wolpi.extension.PyPiInstaller;
import dev.mdz.wolpi.validation.CliRunner.ValidationCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.commons.io.FileUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@DisplayName("The `validate` CLI command")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test-cli-isolated")
class CliRunnerTest {
    @Autowired
    private CliRunner cliRunner;

    @Autowired
    private ValidationCommand validationCommand;

    @Autowired
    private WolpiConfig wolpiConfig;

    @Autowired
    private PyPiInstaller pyPiInstaller;

    @Autowired
    private NpmInstaller npmInstaller;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        Consumer<Integer> throwingHandler = code -> {
            throw new RuntimeException("Exit with code: " + code);
        };
        cliRunner.exitHandler = throwingHandler;
        validationCommand.exitHandler = throwingHandler;
        FileUtils.deleteQuietly(wolpiConfig.dataDirectory().resolve("npm").toFile());
        Files.createDirectories(wolpiConfig.dataDirectory().resolve("npm"));
        try (var ds = Files.newDirectoryStream(wolpiConfig.dataDirectory().resolve("pypi"))) {
            for (Path path : ds) {
                if (path.getFileName().toString().equals("iiif-validator-ng")) {
                    continue;
                }
                FileUtils.deleteQuietly(path.toFile());
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    @Test
    @DisplayName("should fail on non-existing extensions without displaying a stack trace")
    @DirtiesContext
    void shouldFailOnNonExistingExtensionWithoutStackTrace(CapturedOutput output) {
        assertThatThrownBy(() -> cliRunner.run("validate", "/tmp/doesnotexist"))
                .hasMessageContaining("Exit with code: 1");
        assertThat(output.getOut())
                .contains("Failed to load extension")
                .contains("/tmp/doesnotexist")
                .doesNotContain("ExtensionLoadException")
                .doesNotContain("at wolpi/");
        assertThat(output.getErr()).contains("Exit with code: 1").doesNotContain("at wolpi/");
    }

    @DisplayName("should fail on missing mandatory hooks: ")
    @ParameterizedTest(name = "'{1}' in {0}")
    @CsvSource({"invalid.js,info", "invalid.py,cleanup"})
    @DirtiesContext
    void shouldPrintMissingInfoHook(String testFile, String missingHook, CapturedOutput output) {
        String extensionPath = "src/test/resources/%s".formatted(testFile);
        assertThatThrownBy(() -> cliRunner.run("validate", extensionPath)).hasMessageContaining("Exit with code: 1");
        assertThat(output.getOut())
                .contains("Failed to load extension from %s".formatted(extensionPath))
                .contains("Extension does not provide the mandatory '%s' hook, cannot load.".formatted(missingHook))
                .doesNotContain("at wolpi/");
        assertThat(output.getErr()).contains("Exit with code: 1").doesNotContain("at wolpi/");
    }

    @ParameterizedTest(name = "should validate and re-validate on changes in watch mode for {1}-based extension {0}")
    @CsvSource({"test.js,file", "test.py,file", "js-extension,package"})
    @DisplayName("should on changes in watch mode")
    @DirtiesContext
    void shouldValidateOnChangesInWatchMode(String originalSource, String sourceType, CapturedOutput output)
            throws Exception {
        runWatchModeValidation(originalSource, sourceType, output);
    }

    @Test
    @Disabled("GraalPy editable package live reload is not stable enough for default runs")
    @DisplayName("should on changes in watch mode for Python packages")
    @DirtiesContext
    void shouldValidateOnChangesInWatchModeForPythonPackage(CapturedOutput output) throws Exception {
        runWatchModeValidation("py-extension", "package", output);
    }

    private void runWatchModeValidation(String originalSource, String sourceType, CapturedOutput output)
            throws Exception {
        Duration timeout = sourceType.equals("package") && originalSource.startsWith("py-")
                ? Duration.ofSeconds(180)
                : Duration.ofSeconds(60);
        if (sourceType.equals("package") && originalSource.startsWith("py-")) {
            assumeTrue(
                    pyPiInstaller.supportsEditableInstalls(),
                    "Graal Python not available, skipping python package test.");
        } else if (sourceType.equals("package") && originalSource.startsWith("js-")) {
            assumeTrue(
                    npmInstaller.supportsPackageLiveReload(), "NPM version 10+ required for linked local extensions");
        }
        FileUtils.deleteQuietly(
                wolpiConfig.dataDirectory().resolve("pypi", originalSource).toFile());
        FileUtils.deleteQuietly(
                wolpiConfig.dataDirectory().resolve("npm", originalSource).toFile());
        Path extensionPath = tempDir.resolve(originalSource);
        Path sourcePath = Paths.get("src/test/resources/%s".formatted(originalSource));
        if (sourceType.equals("file")) {
            Files.copy(sourcePath, extensionPath);
        } else {
            FileUtils.copyDirectory(sourcePath.toFile(), extensionPath.toFile());
        }
        AtomicReference<Throwable> cliThreadFailure = new AtomicReference<>();
        AtomicReference<Throwable> validationFailure = new AtomicReference<>();
        AtomicReference<Boolean> validationResult = new AtomicReference<>();
        // Capture explicit watch-mode reload outcomes from the command instead of relying on
        // INFO-level log messages, which are intentionally suppressed in test runs.
        validationCommand.validationErrorHandler = validationFailure::set;
        validationCommand.validationResultHandler = validationResult::set;
        Thread cliThread = new Thread(() -> {
            try {
                cliRunner.run("validate", "-w", extensionPath.toAbsolutePath().toString());
            } catch (Exception e) {
                if (e instanceof RuntimeException rte && rte.getMessage().startsWith("Exit with code: 0")) {
                    return;
                }
                cliThreadFailure.set(e);
            }
        });
        cliThread.setDaemon(true);
        try {
            cliThread.start();
            waitFor(
                    timeout,
                    cliThreadFailure,
                    cliThread::isAlive,
                    // The command parks inside validateContinuously once the file watcher is
                    // registered, which is a more stable synchronization point than log capture.
                    () -> isSleepingInMethod(cliThread, "validateContinuously"),
                    "CLI never entered watch mode");

            Path codePath;
            if (Files.isDirectory(extensionPath) && originalSource.startsWith("py-")) {
                codePath = extensionPath.resolve("py_extension", "ext.py");
            } else if (Files.isDirectory(extensionPath) && originalSource.startsWith("js-")) {
                codePath = extensionPath.resolve("index.js");
            } else {
                codePath = extensionPath;
            }
            String extensionCode = Files.readString(codePath);
            // Need to use System.out here because CapturedOutput doesn't see `print()` calls from the
            // Graal Python context
            Files.writeString(
                    codePath,
                    extensionCode.replaceFirst("(#|//) CHANGE THIS LINE FOR TESTS", "System.out.println(\"MODIFIED\")"),
                    StandardOpenOption.WRITE);
            waitFor(
                    timeout,
                    cliThreadFailure,
                    cliThread::isAlive,
                    () -> validationFailure.get() != null || validationResult.get() != null,
                    "CLI never reported a successful re-validation");
        } finally {
            cliThread.interrupt();
            cliThread.join(timeout.toMillis());
            if (cliThread.isAlive()) {
                Assertions.fail("CLI watch thread did not stop within %s".formatted(timeout));
            }
        }
        if (cliThreadFailure.get() != null) {
            Assertions.fail("CLI watch thread failed", cliThreadFailure.get());
        }
        if (validationFailure.get() != null) {
            Assertions.fail("CLI watch validation failed", validationFailure.get());
        }
        assertThat(validationResult.get()).isTrue();
        assertThat(output.getOut()).contains("MODIFIED");
    }

    private static void waitFor(
            Duration timeout,
            AtomicReference<Throwable> cliThreadFailure,
            java.util.function.BooleanSupplier threadAlive,
            java.util.function.BooleanSupplier condition,
            String timeoutMessage)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (cliThreadFailure.get() != null) {
                Assertions.fail("CLI watch thread failed", cliThreadFailure.get());
            }
            if (condition.getAsBoolean()) {
                return;
            }
            if (!threadAlive.getAsBoolean()) {
                break;
            }
            Thread.sleep(250);
        }
        if (cliThreadFailure.get() != null) {
            Assertions.fail("CLI watch thread failed", cliThreadFailure.get());
        }
        Assertions.fail(timeoutMessage);
    }

    private static boolean isSleepingInMethod(Thread thread, String methodName) {
        if (!thread.isAlive() || thread.getState() != Thread.State.TIMED_WAITING) {
            return false;
        }
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getMethodName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }
}
