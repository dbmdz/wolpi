package dev.mdz.wolpi.config;

import dev.mdz.wolpi.config.WolpiConfig.LogFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/// Allows overriding spring properties based on entries in the [WolpiConfig] section.
///
/// The [WolpiConfig] values can either come from the Spring Boot standard sources, or from a custom
/// config file (specified via CLI flag or environment variable) that only has the sections below
/// `wolpi`, with any additional spring configuration properties specified there as well.
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
public class WolpiEnvironmentPostProcessor implements EnvironmentPostProcessor {
    private static final String CONFIG_ENV_PROP = "wolpi.config";
    private static final String CONFIG_CLI_PROP = "config";
    // FIXME: For some reason the Log4J throwable filter ignores the first and the last entries, so
    //        we need to add dummy entries there to make it work as intended.
    private static final List<String> LOGGING_FILTERS = List.of(
            "========================",
            "org.apache.tomcat",
            "org.apache.catalina",
            "org.apache.coyote",
            "java.lang.reflect",
            "jdk.internal.reflect",
            "jdk.proxy2",
            "java.lang.Thread",
            "jakarta.servlet",
            "org.springframework.web",
            "org.springframework.aop",
            "org.graalvm.polyglot",
            "========================");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Load external configuration
        var externalConfigSource = getExternalConfigSource(environment);
        if (externalConfigSource != null) {
            environment.getPropertySources().addFirst(externalConfigSource);
        }

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
            application.setLogStartupInfo(true);
        } else {
            // For text logging, we print our own startup info
            application.setLogStartupInfo(false);
        }

        environment.getPropertySources().addFirst(src);
    }

    private @Nullable EnumerablePropertySource<Object> getExternalConfigSource(ConfigurableEnvironment env) {
        // Look for config file path in CLI args or environment variable
        String pathSpec = Stream.of(CONFIG_CLI_PROP, CONFIG_ENV_PROP)
                .flatMap(e -> Optional.ofNullable(env.getProperty(e)).stream())
                .findFirst()
                .orElse(null);
        // Otherwise check if a default config file exists in the working directory under the
        // assumed filenames
        if (pathSpec == null || pathSpec.isEmpty()) {
            pathSpec = Stream.of("wolpi.yml", "wolpi.yaml")
                    .filter(name -> Files.exists(Paths.get(name)))
                    .findFirst()
                    .orElse(null);
        }
        // Otherwise assume no external configuration
        if (pathSpec == null || pathSpec.isEmpty()) {
            return null;
        }

        FileSystemResource resource = new FileSystemResource(pathSpec);
        if (!resource.exists()) {
            throw new IllegalStateException("Specified config file does not exist: " + pathSpec);
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            List<PropertySource<?>> allSources = loader.load("external-wolpi-config", resource);
            Map<String, Object> processedProps = new HashMap<>();
            Map<String, Object> wolpiProps = new HashMap<>();
            for (PropertySource<?> source : allSources) {
                if (!(source instanceof EnumerablePropertySource<?>)) {
                    continue;
                }
                for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                    Object value = source.getProperty(name);
                    if (name.startsWith("spring.") && value != null) {
                        processedProps.put(name.substring("spring.".length()), value);
                    } else {
                        wolpiProps.put("wolpi.%s".formatted(name), value);
                    }
                }
            }
            var out = new CompositePropertySource("custom-external-config");
            out.addPropertySource(new MapPropertySource("external-spring-config", processedProps));
            out.addPropertySource(new MapPropertySource("external-wolpi-config", wolpiProps));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load external config file: " + pathSpec, e);
        }
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
                        "%clr(%d{HH:mm:ss.SSS}){faint} "
                                + "%replace(%replace(%replace(%replace(%replace(%level)"
                                + "{'ERROR','\uD83D\uDCA3'})"
                                + "{'WARN','⚠️'})"
                                + "{'INFO','\uD83D\uDCAC'})"
                                + "{'DEBUG','\uD83D\uDC1B'})"
                                + "{'TRACE','\uD83D\uDC63'} "
                                + "%magenta(%logger{0}) %clr(%m) %X%n"
                                + "%rEx{8,filters(" + String.join(",", LOGGING_FILTERS) + ")}");
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
}
