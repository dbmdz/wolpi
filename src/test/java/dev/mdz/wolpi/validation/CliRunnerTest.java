package dev.mdz.wolpi.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.ExtensionRegistry;
import dev.mdz.wolpi.extension.GraalContextSupplier;
import dev.mdz.wolpi.extension.GuestContextFactory;
import dev.mdz.wolpi.extension.NpmInstaller;
import dev.mdz.wolpi.extension.PyPiInstaller;
import dev.mdz.wolpi.extension.PyPiInstaller.EntryPoint;
import dev.mdz.wolpi.validation.CliRunner.InstallExtensionsCommand;
import dev.mdz.wolpi.validation.CliRunner.InstallValidatorCommand;
import dev.mdz.wolpi.validation.CliRunner.ValidationCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import picocli.CommandLine;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("The `validate` CLI command")
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliRunnerTest {
    private static final int SERVER_PORT = 12345;

    private CliRunner cliRunner;
    private ValidationCommand validationCommand;
    private WolpiConfig wolpiConfig;
    private PyPiInstaller pyPiInstaller;
    private NpmInstaller npmInstaller;
    private ImageApiValidator imageApiValidator;
    private GraalContextSupplier contextSupplier;
    private ExtensionRegistry registry;
    private GuestContextFactory guestContextFactory;
    private Arena arena;
    private SimpleMeterRegistry meterRegistry;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        registry = null;
        guestContextFactory = null;
        arena = null;
        meterRegistry = null;
        // Exercise real extension loading and file watching without starting an HTTP server or
        // rerunning conformance validation. CliRunnerIntegrationTest covers the real validator.
        wolpiConfig = new WolpiConfig(
                tempDir,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                new WolpiConfig.PackagingConfig(null, null, Duration.ofSeconds(30)),
                null,
                null,
                Map.of(),
                null);
        var mapper = new JsonMapper();
        pyPiInstaller = mock(PyPiInstaller.class);
        when(pyPiInstaller.supportsEditableInstalls()).thenReturn(true);
        npmInstaller = new NpmInstaller(wolpiConfig, mapper);
        imageApiValidator = mock(ImageApiValidator.class);
        if (contextSupplier == null) {
            contextSupplier = new GraalContextSupplier(wolpiConfig);
        }
        var buildProperties = mock(BuildProperties.class);
        when(buildProperties.getVersion()).thenReturn("test");
        arena = Arena.ofShared();
        meterRegistry = new SimpleMeterRegistry();
        guestContextFactory = new GuestContextFactory(buildProperties, wolpiConfig, arena, null, meterRegistry);
        registry = new ExtensionRegistry(
                wolpiConfig,
                pyPiInstaller,
                npmInstaller,
                mock(GenericKeyedObjectPool.class),
                contextSupplier,
                guestContextFactory,
                null);
        validationCommand = new ValidationCommand(registry, imageApiValidator, pyPiInstaller, npmInstaller);
        var event = mock(WebServerInitializedEvent.class, RETURNS_DEEP_STUBS);
        when(event.getWebServer().getPort()).thenReturn(SERVER_PORT);
        validationCommand.onApplicationEvent(event);
        cliRunner = new CliRunner(new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == ValidationCommand.class) {
                    return cls.cast(validationCommand);
                }
                if (cls == InstallValidatorCommand.class) {
                    return cls.cast(new InstallValidatorCommand(imageApiValidator));
                }
                if (cls == InstallExtensionsCommand.class) {
                    return cls.cast(new InstallExtensionsCommand(wolpiConfig, mock(ValidatingRunner.class)));
                }
                return CommandLine.defaultFactory().create(cls);
            }
        });
        Consumer<Integer> throwingHandler = code -> {
            throw new RuntimeException("Exit with code: " + code);
        };
        cliRunner.exitHandler = throwingHandler;
        validationCommand.exitHandler = throwingHandler;
    }

    @AfterEach
    void closeFixtures() throws Exception {
        try {
            if (registry != null) {
                registry.close();
            }
        } finally {
            if (guestContextFactory != null) {
                guestContextFactory.close();
            }
            if (arena != null) {
                arena.close();
            }
            if (meterRegistry != null) {
                meterRegistry.close();
            }
        }
    }

    @AfterAll
    void releaseEngineCaches() {
        if (contextSupplier != null) {
            contextSupplier.resetEngine();
        }
    }

    @Test
    @DisplayName("should fail on non-existing extensions without displaying a stack trace")
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
    void shouldPrintMissingInfoHook(String testFile, String missingHook, CapturedOutput output) {
        String extensionPath = "src/test/resources/%s".formatted(testFile);
        assertThatThrownBy(() -> cliRunner.run("validate", extensionPath)).hasMessageContaining("Exit with code: 1");
        assertThat(output.getOut())
                .contains("Failed to load extension from %s".formatted(extensionPath))
                .contains("Extension does not provide the mandatory '%s' hook, cannot load.".formatted(missingHook))
                .doesNotContain("at wolpi/");
        assertThat(output.getErr()).contains("Exit with code: 1").doesNotContain("at wolpi/");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reportsValidatorOutcome(boolean valid) {
        when(imageApiValidator.validateExtension(any(), eq(SERVER_PORT))).thenReturn(valid);

        assertThatThrownBy(() -> cliRunner.run("validate", "src/test/resources/test.js"))
                .hasMessageContaining("Exit with code: " + (valid ? 0 : 1));

        verify(imageApiValidator).installValidator();
        verify(imageApiValidator)
                .validateExtension(
                        argThat(ext -> ext.config().path().equals(Path.of("src/test/resources/test.js"))),
                        eq(SERVER_PORT));
    }

    @ParameterizedTest(name = "should validate and re-validate on changes in watch mode for {1}-based extension {0}")
    @CsvSource({"test.js,file", "test.py,file", "js-extension,package"})
    @DisplayName("should on changes in watch mode")
    void shouldValidateOnChangesInWatchMode(String originalSource, String sourceType, CapturedOutput output)
            throws Exception {
        runWatchModeValidation(originalSource, sourceType, output);
    }

    @Test
    @DisplayName("should on changes in watch mode for Python packages")
    void shouldValidateOnChangesInWatchModeForPythonPackage(CapturedOutput output) throws Exception {
        runWatchModeValidation("py-extension", "package", output);
    }

    @Test
    void reportsFailedRevalidation(CapturedOutput output) throws Exception {
        runWatchModeValidation("test.js", "file", output, false, null);
    }

    @Test
    void reportsRevalidationException(CapturedOutput output) throws Exception {
        runWatchModeValidation("test.js", "file", output, false, new IllegalStateException("validation failed"));
    }

    private void runWatchModeValidation(String originalSource, String sourceType, CapturedOutput output)
            throws Exception {
        runWatchModeValidation(originalSource, sourceType, output, true, null);
    }

    private void runWatchModeValidation(
            String originalSource,
            String sourceType,
            CapturedOutput output,
            boolean valid,
            RuntimeException expectedFailure)
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
        if (sourceType.equals("package") && originalSource.startsWith("py-")) {
            var sitePackages = createFakePythonPackageVenv(extensionPath);
            when(pyPiInstaller.installExtensionFromLocalDirectory(extensionPath.toAbsolutePath(), true))
                    .thenReturn("py-extension");
            when(pyPiInstaller.getVenvSitePackages("py-extension")).thenReturn(sitePackages);
            when(pyPiInstaller.getWolpiEntryPoint("py-extension"))
                    .thenReturn(new EntryPoint("py_extension", "wolpi_extension"));
            when(pyPiInstaller.getVersion("py-extension")).thenReturn("1.0.0");
        }
        if (expectedFailure == null) {
            when(imageApiValidator.validateExtension(any(), eq(SERVER_PORT))).thenReturn(valid);
        } else {
            when(imageApiValidator.validateExtension(any(), eq(SERVER_PORT))).thenThrow(expectedFailure);
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
        if (expectedFailure == null) {
            assertThat(validationFailure.get()).isNull();
            assertThat(validationResult.get()).isEqualTo(valid);
        } else {
            assertThat(validationFailure.get()).isSameAs(expectedFailure);
            assertThat(validationResult.get()).isNull();
        }
        assertThat(output.getOut()).contains("MODIFIED");
        verify(imageApiValidator).installValidator();
        verify(imageApiValidator, atLeastOnce())
                .validateExtension(
                        argThat(ext -> ext.lastModified() != null
                                && ext.config().path().equals(extensionPath)),
                        eq(SERVER_PORT));
    }

    private Path createFakePythonPackageVenv(Path extensionPath) throws IOException {
        Path venv = tempDir.resolve("venv");
        Path bin = Files.createDirectories(venv.resolve("bin"));
        Path graalpy = Files.createFile(bin.resolve("graalpy"));
        graalpy.toFile().setExecutable(true);
        Path sitePackages = Files.createDirectories(venv.resolve("lib/python3.12/site-packages"));
        // Import the live extension directory, so edits through the watcher are visible without
        // running a real editable package install.
        Files.createSymbolicLink(sitePackages.resolve("py_extension"), extensionPath.resolve("py_extension"));
        return sitePackages;
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
