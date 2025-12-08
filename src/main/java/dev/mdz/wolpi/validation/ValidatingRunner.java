package dev.mdz.wolpi.validation;

import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.ExtensionRegistry;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/// Runner that performs validation of extensions at application startup
///
/// Each extension is tested in isolation against the official IIIF Image API validation test suite
/// and if any extension fails, the details are logged and the application is terminated with a
/// non-zero exit code.
@Profile("!test") // Don't run as part of default integration tests
@Component
public class ValidatingRunner implements ApplicationRunner, ApplicationListener<WebServerInitializedEvent> {
    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final ExtensionRegistry extensionRegistry;
    private final ImageApiValidator validator;
    private final ExtensionValidationCache validationCache;
    private final ExtensionHashCalculator hashCalculator;
    private final ApplicationEventPublisher publisher;
    private final ConfigurableApplicationContext context;
    private final WolpiConfig config;

    // Injected once Tomcat is ready to serve requests
    private int serverPort;

    public ValidatingRunner(
            ExtensionRegistry extensionRegistry,
            ImageApiValidator validator,
            ExtensionValidationCache validationCache,
            ExtensionHashCalculator hashCalculator,
            ApplicationEventPublisher publisher,
            ConfigurableApplicationContext context,
            WolpiConfig config) {
        this.extensionRegistry = extensionRegistry;
        this.validator = validator;
        this.validationCache = validationCache;
        this.hashCalculator = hashCalculator;
        this.publisher = publisher;
        this.context = context;
        this.config = config;
    }

    public boolean validateAllExtensions() {
        record ExtensionWithHash(LoadedExtension extension, String hash) {}
        var allExtensions = extensionRegistry.getExtensions();
        var extensionsToValidate = allExtensions.stream()
                .map(ext -> new ExtensionWithHash(ext, hashCalculator.calculateHash(ext)))
                .filter(ewh -> {
                    if (validationCache.isValidated(ewh.extension, ewh.hash)) {
                        log.debug(
                                "Skipping validation for extension '{}' (hash: {}), already validated",
                                ewh.extension.extensionInfo().name(),
                                ewh.hash);
                        return false;
                    }
                    return true;
                })
                .toList();

        if (extensionsToValidate.isEmpty()) {
            log.info("All {} extension(s) have been validated previously, skipping validation", allExtensions.size());
        } else {
            log.info(
                    "Starting validation of {} extension(s) ({} cached, {} to validate)",
                    allExtensions.size(),
                    allExtensions.size() - extensionsToValidate.size(),
                    extensionsToValidate.size());

            for (var ewh : extensionsToValidate) {
                log.info(
                        "Validating extension '{}'...",
                        ewh.extension.extensionInfo().name());
                if (!validator.validateExtension(ewh.extension, this.serverPort)) {
                    log.error(
                            "Validation of extension '{}' failed, fix or remove and restart.",
                            ewh.extension.extensionInfo().name());
                    return false;
                }
                validationCache.markValidated(ewh.extension, ewh.hash);
            }

            log.info("Extension validation successful for all {} registered extension(s).", allExtensions.size());
        }
        return true;
    }

    /// Run validation on all registered extensions after the web server is ready.
    ///
    /// The application is marked as REFUSING_TRAFFIC during validation and only marked as
    /// ACCEPTING_TRAFFIC if all extensions pass validation. This ensures that health checks
    /// can run against the application during validation, but no external traffic is routed to it.
    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Don't validate if we have no extensions, or if we're running in a mock servlet context,
        // where the validation tests don't have access to a real HTTP server.
        boolean skipValidation = extensionRegistry.getExtensions().isEmpty() || this.serverPort == 0;
        if (skipValidation) {
            log.info(
                    "Listening on {}:{}",
                    this.config.http() == null
                            ? "localhost"
                            : this.config.http().host(),
                    this.serverPort);
            return;
        }
        AvailabilityChangeEvent.publish(this.publisher, this, ReadinessState.REFUSING_TRAFFIC);

        if (!this.validateAllExtensions()) {
            int exitCode = SpringApplication.exit(this.context, () -> 1);
            System.exit(exitCode);
        }

        AvailabilityChangeEvent.publish(this.publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        log.info(
                "Listening on {}:{}",
                this.config.http() == null ? "localhost" : this.config.http().host(),
                this.serverPort);
    }

    /// Capture the server port once Tomcat is initialized
    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.serverPort = event.getWebServer().getPort();
    }
}
