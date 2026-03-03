package dev.mdz.wolpi.validation;

import dev.mdz.wolpi.config.ExtensionConfig;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.ExtensionRegistry;
import dev.mdz.wolpi.extension.NpmInstaller;
import dev.mdz.wolpi.extension.PyPiInstaller;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.validation.CliRunner.InstallExtensionsCommand;
import dev.mdz.wolpi.validation.CliRunner.InstallValidatorCommand;
import dev.mdz.wolpi.validation.CliRunner.ValidationCommand;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IFactory;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Runner that provides a "validate" CLI command to validate a Wolpi extension
/// using the IIIF Image API validation suite and a "install-validator" command
/// to pre-install the validation suite (e.g. for building a container image).
///
/// The actual subcommands are implemented in the inner classes.
@Component
@Command(
        mixinStandardHelpOptions = true,
        subcommands = {ValidationCommand.class, InstallValidatorCommand.class, InstallExtensionsCommand.class})
public class CliRunner implements CommandLineRunner, Runnable {

    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final IFactory picoCliFactory;

    Consumer<Integer> exitHandler = System::exit;

    public CliRunner(IFactory picoCliFactory) {
        this.picoCliFactory = picoCliFactory;
    }

    @Override
    public void run(String... args) throws Exception {
        // Pass over to picocli if "validate" command is given
        if (Arrays.stream(args).anyMatch(arg -> List.of("validate", "install-validator", "install-extensions")
                .contains(arg))) {
            var filteredArgs = Arrays.stream(args)
                    .filter(arg -> !arg.startsWith("--spring.")
                            && !arg.startsWith("-D")
                            && !arg.startsWith("--wolpi.")
                            && !arg.startsWith("--config"))
                    .toArray(String[]::new);
            int exitCode = new CommandLine(this, picoCliFactory).execute(filteredArgs);
            exitHandler.accept(exitCode);
        }
        // Otherwise, just run the Spring boot default startup sequence
    }

    @Override
    public void run() {
        // NOP, this will never be run since we don't have a 'main' command
    }

    /// Subcommand that performs the actual validation of a Wolpi extension.
    ///
    /// Detects changes to the extension file or directory and re-runs the validation automatically
    /// if the `--watch` option is given.
    @Component
    @Command(name = "validate", description = "Validate a local Wolpi extension")
    public static class ValidationCommand implements Runnable, ApplicationListener<WebServerInitializedEvent> {

        private final ExtensionRegistry extensionRegistry;
        private final ImageApiValidator imageApiValidator;
        private final PyPiInstaller pyPiInstaller;
        private final NpmInstaller npmInstaller;

        @Parameters(
                index = "0",
                arity = "1",
                description = "Path to the Wolpi extension directory to validate",
                paramLabel = "<extension-path>")
        private File extensionLocation;

        @Option(
                names = {"-w", "--watch"},
                description = "Watch the extension file or directory for changes and re-validate on change")
        private boolean watch = false;

        private int serverPort = -1;
        Consumer<Integer> exitHandler = System::exit;

        public ValidationCommand(
                ExtensionRegistry extensionRegistry,
                ImageApiValidator imageApiValidator,
                PyPiInstaller pyPiInstaller,
                NpmInstaller npmInstaller) {
            this.extensionRegistry = extensionRegistry;
            this.imageApiValidator = imageApiValidator;
            this.pyPiInstaller = pyPiInstaller;
            this.npmInstaller = npmInstaller;
        }

        @Override
        public void run() {
            if (watch && Files.isDirectory(extensionLocation.toPath())) {
                if (Files.exists(extensionLocation.toPath().resolve("pyproject.toml"))
                        && !pyPiInstaller.supportsEditableInstalls()) {
                    log.error(
                            "Watching local Python packages requires a standalone GraalPy {} executable, which is not available on the PATH. Please make sure a `graalpy` executable is installed and available on the PATH.",
                            PyPiInstaller.EXPECTED_GRAALPY_VERSION);
                    exitHandler.accept(1);
                } else if (Files.exists(extensionLocation.toPath().resolve("package.json"))
                        && !npmInstaller.supportsPackageLiveReload()) {
                    log.error(
                            "Watching local NPM packages requires NPM version 10 or higher. Please upgrade your NPM installation.");
                    exitHandler.accept(1);
                }
            }
            imageApiValidator.installValidator();
            var ext = new ExtensionConfig(extensionLocation.toPath(), null, null, null, watch);
            LoadedExtension loadedExt = null;

            try {
                log.info(
                        "Installing extension from {}{}",
                        extensionLocation.getAbsolutePath(),
                        watch ? " in editable mode" : "");
                loadedExt = extensionRegistry.loadExtension(ext);
            } catch (ExtensionLoadException e) {
                log.error("Failed to load extension from {}: {}", extensionLocation, e.getMessage());
            }

            if (loadedExt == null) {
                exitHandler.accept(1);
                assert false; // exitHandler will always exit
            }

            if (watch) {
                try {
                    validateContinuously(loadedExt, extensionLocation);
                } catch (Exception e) {
                    log.error("Failed to watch extension location {}", extensionLocation, e);
                    exitHandler.accept(1);
                }
            } else {
                log.info(
                        "Validating the Wolpi extension at '{}' against server running on port {}",
                        extensionLocation.getAbsolutePath(),
                        serverPort);
                if (!validate(loadedExt)) {
                    log.error("Extension validation failed.");
                    exitHandler.accept(1);
                } else {
                    log.info("Extension validation successful.");
                }
            }
        }

        /// Will be run before [#run()], so [#serverPort] should always be > 0 when [#run()] is
        /// called.
        @Override
        public void onApplicationEvent(WebServerInitializedEvent event) {
            String namespace = event.getApplicationContext().getServerNamespace();
            if (namespace != null && !namespace.isBlank()) {
                return;
            }
            this.serverPort = event.getWebServer().getPort();
        }

        /// Monitor an extension file or directory for modifications and run the validation test
        /// suite against the extension on every change.
        private void validateContinuously(LoadedExtension ext, File location) throws Exception {
            extensionRegistry.addReloadCallback(ext.config(), (updatedExtension) -> {
                log.info(
                        "File change detected in {}, re-validating extension...",
                        updatedExtension.extensionInfo().name());
                try {
                    if (!validate(updatedExtension)) {
                        log.warn("Extension validation failed.");
                    } else {
                        log.info("Extension validation successful.");
                    }
                } catch (Exception e) {
                    log.error("Error during extension validation", e);
                }
            });
            log.info("Watching {} for changes, press Ctrl-C to exit.", location.getAbsolutePath());
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean validate(LoadedExtension ext) {
            try (var _ = extensionRegistry.temporarilyIsolateExtension(ext)) {
                return imageApiValidator.validateExtension(ext, this.serverPort);
            }
        }
    }

    @Component
    @Command(name = "install-validator", description = "Install the IIIF Image API validation suite")
    public static class InstallValidatorCommand implements Runnable {

        private final ImageApiValidator imageApiValidator;

        Consumer<Integer> exitHandler = System::exit;

        public InstallValidatorCommand(ImageApiValidator imageApiValidator) {
            this.imageApiValidator = imageApiValidator;
        }

        @Override
        public void run() {
            imageApiValidator.installValidator();
            exitHandler.accept(0);
        }
    }

    // Don't run as part of default integration tests, since ValidatingRunner is unavailable there
    @Profile("!test")
    @Component
    @Command(name = "install-extensions", description = "Install and verify extensions from the configuration")
    public static class InstallExtensionsCommand implements Runnable {

        private final WolpiConfig wolpiConfig;
        private final ValidatingRunner validatingRunner;

        Consumer<Integer> exitHandler = System::exit;

        public InstallExtensionsCommand(WolpiConfig wolpiConfig, ValidatingRunner validatingRunner) {
            this.wolpiConfig = wolpiConfig;
            this.validatingRunner = validatingRunner;
        }

        @Override
        public void run() {
            if (wolpiConfig.extensions().isEmpty()) {
                log.info("No extensions found in configuration, exiting.");
                exitHandler.accept(0);
            }
            int exitCode = 0;
            if (validatingRunner.validateAllExtensions()) {
                log.info("Extensions installed and validated successfully.");
            } else {
                exitCode = 1;
            }
            exitHandler.accept(exitCode);
        }
    }
}
