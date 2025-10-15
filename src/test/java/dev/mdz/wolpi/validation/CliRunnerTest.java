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
import java.util.function.Consumer;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
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
    }

    // NOTE: This test takes *AGES* (~90sec on my laptop) because pip+venv in GraalPy seems to be
    //       extremely slow.
    @ParameterizedTest(name = "should validate and re-validate on changes in watch mode for {1}-based extension {0}")
    @CsvSource({"test.js,file", "test.py,file", "js-extension,package", "py-extension,package"})
    @DisplayName("should on changes in watch mode")
    @DirtiesContext
    void shouldValidateOnChangesInWatchMode(String originalSource, String sourceType, CapturedOutput output)
            throws Exception {
        if (sourceType.equals("package") && originalSource.startsWith("py-")) {
            assumeTrue(
                    pyPiInstaller.supportsPackageLiveReload(),
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
        Thread cliThread = new Thread(() -> {
            try {
                cliRunner.run("validate", "-w", extensionPath.toAbsolutePath().toString());
            } catch (Exception e) {
                if (e instanceof RuntimeException rte && rte.getMessage().startsWith("Exit with code: 0")) {
                    return;
                }
                throw new RuntimeException(e);
            }
        });
        try {
            cliThread.start();
            // Busy wait until we can be sure the path is being watched
            while (cliThread.isAlive()
                    && !output.getOut()
                            .contains("Watching %s for changes"
                                    .formatted(extensionPath.toAbsolutePath().toString()))) {
                Thread.sleep(250);
            }

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
            // Busy wait until the test suite is complete
            while (cliThread.isAlive() && !output.getOut().contains("xtension validation")) {
                Thread.sleep(250);
            }
        } finally {
            System.err.println("Stopping CLI thread");
            cliThread.interrupt();
            cliThread.join();
        }
        assertThat(output.getOut()).contains("MODIFIED").contains("Extension validation successful.");
    }
}
