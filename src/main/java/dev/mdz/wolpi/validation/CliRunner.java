package dev.mdz.wolpi.validation;

import dev.mdz.wolpi.config.ExtensionConfig;
import dev.mdz.wolpi.extension.ExtensionRegistry;
import dev.mdz.wolpi.extension.RuntimeContext;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.validation.CliRunner.ValidationCommand;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.apache.commons.pool2.KeyedObjectPool;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IFactory;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Runner that provides a "validate" CLI command to validate a Wolpi extension
/// using the IIIF Image API validation suite.
///
/// The actual subcommand is implemented in the inner class [ValidationCommand].
@Component
@Command(
        name = "validate",
        description = "Validate a Wolpi extension",
        mixinStandardHelpOptions = true,
        subcommands = {ValidationCommand.class})
public class CliRunner implements CommandLineRunner, Runnable {
    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final IFactory picoCliFactory;

    public CliRunner(
            IFactory picoCliFactory,
            KeyedObjectPool<LoadedExtension, RuntimeContext> extensionContextPool,
            ExtensionRegistry registry) {
        this.picoCliFactory = picoCliFactory;
    }

    @Override
    public void run(String... args) throws Exception {
        // Pass over to picocli if "validate" command is given
        if (Arrays.stream(args).anyMatch(arg -> arg.equals("validate"))) {
            int exitCode = new CommandLine(this, picoCliFactory).execute(args);
            System.exit(exitCode);
        }
        // Otherwise, just run the Spring boot default startup sequence
    }

    @Override
    public void run() {
        // NOP, this will never be run since we don't have a 'main' command
    }

    /// Subcommand that performs the actual validation of a Wolpi extension.
    ///
    /// Detects changes to the extension file or directory and re-runs the validation
    /// automatically if the `--watch` option is given.
    @Component
    @Command(name = "validate", description = "Validate a local Wolpi extension")
    public static class ValidationCommand implements Runnable, ApplicationListener<WebServerInitializedEvent> {
        private static final Set<String> WATCHED_FILE_EXTENSIONS = Set.of(".js", ".mjs", ".py");

        private final ExtensionRegistry extensionRegistry;
        private final KeyedObjectPool<LoadedExtension, RuntimeContext> extensionPool;
        private final ImageApiValidator imageApiValidator;

        @Parameters(
                index = "0",
                arity = "1",
                description = "Path to the Wolpi extension directory to validate",
                paramLabel = "<extension-path>")
        private @Nullable File extensionLocation;

        @Option(
                names = {"-w", "--watch"},
                description = "Watch the extension file or directory for changes and re-validate on change")
        private boolean watch = false;

        private int serverPort = -1;

        public ValidationCommand(
                ExtensionRegistry extensionRegistry,
                KeyedObjectPool<LoadedExtension, RuntimeContext> extensionPool,
                ImageApiValidator imageApiValidator) {
            this.extensionRegistry = extensionRegistry;
            this.extensionPool = extensionPool;
            this.imageApiValidator = imageApiValidator;
        }

        @Override
        public void run() {
            imageApiValidator.installValidator();
            var ext = new ExtensionConfig(extensionLocation.toPath(), null, null, null);
            LoadedExtension loadedExt = null;

            try {
                loadedExt = extensionRegistry.loadExtension(ext);
            } catch (ExtensionLoadException e) {
                log.error("Failed to load extension from {}", extensionLocation, e);
            }

            if (loadedExt == null) {
                System.exit(1);
            }

            if (watch) {
                try {
                    validateContinuously(loadedExt, extensionLocation);
                } catch (Exception e) {
                    log.error("Failed to watch extension location {}", extensionLocation, e);
                    System.exit(1);
                }
            } else {
                log.info(
                        "Validating the Wolpi extension at '{}' against server running on port {}",
                        extensionLocation.getAbsolutePath(),
                        serverPort);
                if (!validate(loadedExt)) {
                    log.error("Extension validation failed.");
                    System.exit(1);
                } else {
                    log.info("Extension validation successful.");
                }
            }
        }

        /// Will be run before [#run()], so [#serverPort] should always be > 0 when [#run()] is
        /// called.
        @Override
        public void onApplicationEvent(WebServerInitializedEvent event) {
            this.serverPort = event.getWebServer().getPort();
        }

        /// Monitor an extension file or directory for modifications and run the validation test
        /// suite against the extension on every change.
        private void validateContinuously(LoadedExtension ext, File location) throws Exception {
            FileAlterationObserver observer = FileAlterationObserver.builder()
                    .setFile(extensionLocation.isFile() ? extensionLocation.getParentFile() : extensionLocation)
                    .setFileFilter(f -> extensionLocation.isFile()
                            ? f.equals(extensionLocation)
                            : (f.isFile() && WATCHED_FILE_EXTENSIONS.stream().anyMatch(f.getName()::endsWith)))
                    .get();
            observer.addListener(new FileAlterationListenerAdaptor() {
                private final AtomicLong lastChange = new AtomicLong(-1);

                @Override
                public void onFileChange(File file) {
                    long now = System.nanoTime();
                    if (now - lastChange.get() < 500_000_000) {
                        // Ignore changes within the last 500ms to avoid double-processing
                        return;
                    } else {
                        lastChange.set(now);
                    }
                    LoadedExtension updatedExtension = ext;
                    log.info("File change detected in {}, re-validating extension...", file.getAbsolutePath());
                    // If the extension is a single-file extension, we need to reload the extension
                    // to pick up changes. For multi-file extensions, we just clear the pool
                    // since the changes will be picked up automatically when a new context is created.
                    if (extensionLocation.isFile()) {
                        try {
                            updatedExtension = extensionRegistry.loadExtension(
                                    new ExtensionConfig(extensionLocation.toPath(), null, null, null));
                        } catch (ExtensionLoadException e) {
                            log.error("Failed to reload extension after file change, skipping validation.", e);
                            return;
                        }
                    }
                    // Clear up idle contexts to ensure changes are picked up when running the
                    // validation test suite.
                    if (extensionPool.getNumActive() > 0) {
                        log.info("Waiting for active contexts to be returned, please close all browser tabs...");
                        while (extensionPool.getNumActive() > 0) {
                            // We need to wait for all active contexts to be returned before we can clear
                            // the pool, otherwise we run the risk of old contexts being used in the
                            // validation.
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                log.error(
                                        "Interrupted while waiting for active contexts to be returned, skipping validation.",
                                        e);
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                    try {
                        extensionPool.clear();
                    } catch (Exception e) {
                        log.error("Failed to clear extension context pool, skipping verification.", e);
                        return;
                    }
                    try {
                        if (!validate(updatedExtension)) {
                            log.warn("Extension validation failed.");
                        } else {
                            log.info("Extension validation successful.");
                        }
                    } catch (Exception e) {
                        log.error("Error during extension validation", e);
                    }
                }
            });

            log.info("Watching extension {} for changes, press Ctrl+C to exit.", location);
            while (true) {
                try {
                    observer.checkAndNotify();
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error while monitoring extension files", e);
                    break;
                }
            }
        }

        private boolean validate(LoadedExtension ext) {
            try (var _ = extensionRegistry.temporarilyIsolateExtension(ext)) {
                return imageApiValidator.validateExtension(ext, this.serverPort);
            }
        }
    }
}
