package dev.mdz.wolpi.config;

import dev.mdz.wolpi.config.WolpiConfig.LogFormat;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/// Allows overriding spring properties based on entries in the [WolpiConfig] section
///
/// This is intended to make Spring and its complex configuration "invisible" to end-users,
/// we want to have a single unified configuration file for Wolpi without spring-specific
/// sections. Therefore, we allow configuring common spring properties such as the server port
/// or logging level via the Wolpi configuration section.
///
/// The same goes for logging, instead of the myriad of options spring provides, we only expose
/// two presets: JSON and TEXT, which should cover most use cases.
///
/// Power-Users can simply not specify these options in the Wolpi section and use the spring
/// standard configuration options instead.
public class ConfigOverrideListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        var binder = Binder.get(environment);
        var bound = binder.bind("wolpi", WolpiConfig.class);
        if (!bound.isBound()) {
            return;
        }
        WolpiConfig config = bound.get();

        CompositePropertySource src = new CompositePropertySource("wolpi-overrides");

        src.addPropertySource(getLoggingOverrides(config));
        src.addPropertySource(getHttpOverrides(config));

        if (config.logging() != null && config.logging().format().equals(LogFormat.JSON)) {
            // Startup info is included in JSON logging
            event.getSpringApplication().setLogStartupInfo(true);
        } else {
            // For text logging, we print our own startup info
            event.getSpringApplication().setLogStartupInfo(false);
        }

        environment.getPropertySources().addFirst(src);
    }

    private MapPropertySource getLoggingOverrides(WolpiConfig config) {
        Map<String, Object> overrides = new HashMap<>();
        if (config.logging() != null) {
            overrides.put("logging.level.root", config.logging().level().toString());
            if (config.logging().format() == LogFormat.JSON) {
                overrides.put("logging.structured.format.console", "logstash");
                overrides.put("logging.structured.json.stacktrace.include-common-frames", "false");
                overrides.put("logging.structured.json.stacktrace.include-hashes", "true");
                overrides.put("logging.structured.json.stacktrace.root", "first");
            } else if (config.logging().format() == LogFormat.TEXT) {
                overrides.put(
                        "logging.pattern.console",
                        "%clr(%d{HH:mm:ss.SSS}){faint} %replace(%replace(%replace(%replace(%replace(%level){'ERROR','\uD83D\uDCA3'}){'WARN','⚠️'}){'INFO','\uD83D\uDCAC'}){'DEBUG','\uD83D\uDC1B'}){'TRACE','\uD83D\uDC63'} %clr(%m) %X%n%xEx");
            }
        }
        return new MapPropertySource("wolpi-logging-overrides", overrides);
    }

    private MapPropertySource getHttpOverrides(WolpiConfig config) {
        Map<String, Object> overrides = new HashMap<>();
        var http = config.http();
        if (http != null) {
            if (!http.host().isEmpty()) {
                overrides.put("server.address", http.host());
            }
            if (http.port() > 0) {
                overrides.put("server.port", http.port().toString());
            }
            if (http.minThreads() >= 0) {
                overrides.put("server.tomcat.threads.min-spare", Integer.toString(http.minThreads()));
            }
            if (http.maxThreads() > 0) {
                overrides.put("server.tomcat.threads.max", Integer.toString(http.maxThreads()));
            }
            if (http.maxRequestsAccepted() >= 0) {
                overrides.put("server.tomcat.accept-count", Integer.toString(http.maxRequestsAccepted()));
            }
        }
        return new MapPropertySource("wolpi-http-overrides", overrides);
    }

    @Override
    public int getOrder() {
        // After EnvironmentPostProcessorApplicationListener (so all property sources are loaded),
        // before LoggingApplicationListener (when logging is configured)
        return Ordered.HIGHEST_PRECEDENCE + 11;
    }
}
