package dev.mdz.wolpi.validation;

import dev.mdz.wolpi.extension.ExtensionRegistry;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.web.context.WebServerInitializedEvent;
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
    private final ApplicationEventPublisher publisher;
    private final ConfigurableApplicationContext context;

    // Injected once Tomcat is ready to serve requests
    private int serverPort;

    public ValidatingRunner(
            ExtensionRegistry extensionRegistry,
            ImageApiValidator validator,
            ApplicationEventPublisher publisher,
            ConfigurableApplicationContext context) {
        this.extensionRegistry = extensionRegistry;
        this.validator = validator;
        this.publisher = publisher;
        this.context = context;
    }

    /// Run validation on all registered extensions after the web server is ready.
    ///
    /// The application is marked as REFUSING_TRAFFIC during validation and only marked as
    /// ACCEPTING_TRAFFIC if all extensions pass validation. This ensures that health checks
    /// can run against the application during validation, but no external traffic is routed to it.
    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (extensionRegistry.getExtensions().isEmpty()) {
            log.info("No extensions registered, skipping validation");
            return;
        }
        AvailabilityChangeEvent.publish(this.publisher, this, ReadinessState.REFUSING_TRAFFIC);
        log.info(
                "Starting validation of {} registered extensions",
                extensionRegistry.getExtensions().size());
        for (var ext : extensionRegistry.getExtensions()) {
            try (var _ = extensionRegistry.temporarilyIsolateExtension(ext)) {
                if (!validator.runStartupExtensionValidation(this.serverPort)) {
                    int exitCode = SpringApplication.exit(this.context, () -> 1);
                    System.exit(exitCode);
                }
            }
        }

        log.info(
                "Extension validation successful for all {} registered extensions, accepting traffic.",
                extensionRegistry.getExtensions().size());
        AvailabilityChangeEvent.publish(this.publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    }

    /// Capture the server port once Tomcat is initialized
    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.serverPort = event.getWebServer().getPort();
    }
}
