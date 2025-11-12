package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.extension.mapping.ImageRequestParserProxy;
import dev.mdz.wolpi.extension.model.ExtensionGuestContext;
import dev.mdz.wolpi.extension.model.Language;
import dev.mdz.wolpi.iiif.ImageRequestParser;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.foreign.Arena;
import java.net.http.HttpClient;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/// Factory for creating [ExtensionGuestContext] instances for extensions that give access to
/// various useful runtime objects such as configuration, logging and metrics.
@Component
public class GuestContextFactory {
    private final String wolpiVersion;
    private final HttpClient httpClient;
    private final Arena vipsArena;
    private final ImageRequestParser imageRequestParser;
    private final MeterRegistry meterRegistry;

    public GuestContextFactory(
            BuildProperties buildProps,
            HttpClient httpClient,
            Arena vipsArena,
            ImageRequestParser imageRequestParser,
            MeterRegistry meterRegistry) {
        this.wolpiVersion = buildProps.getVersion();
        this.httpClient = httpClient;
        this.vipsArena = vipsArena;
        this.imageRequestParser = imageRequestParser;
        this.meterRegistry = meterRegistry;
    }

    /// Creates a new [ExtensionGuestContext] for the given extension.
    ///
    /// @param packageName      The package name of the extension, used for logging.
    /// @param extensionVersion The version of the extension.
    /// @param config           The configuration object for the extension, may be null.
    /// @param guestLanguage    The language the extension is running in, used for mapping objects.
    /// @return A new [ExtensionGuestContext] instance.
    public ExtensionGuestContext createGuestContext(
            String packageName, String extensionVersion, @Nullable Map<String, Object> config, Language guestLanguage) {
        return new ExtensionGuestContext(
                wolpiVersion,
                extensionVersion,
                httpClient,
                new ExtensionLogger(packageName),
                config,
                new ExtensionMetrics(meterRegistry),
                vipsArena,
                new ImageRequestParserProxy(imageRequestParser, guestLanguage));
    }
}
