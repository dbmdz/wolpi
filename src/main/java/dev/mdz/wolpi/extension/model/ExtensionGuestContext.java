package dev.mdz.wolpi.extension.model;

import dev.mdz.wolpi.extension.ExtensionLogger;
import dev.mdz.wolpi.extension.ExtensionMetrics;
import dev.mdz.wolpi.extension.mapping.ImageRequestParserProxy;
import java.lang.foreign.Arena;
import java.net.http.HttpClient;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/// Context object available to extensions at runtime, provides access to metadata, the extension
/// configuration, as well as a shared [HttpClient] instance.
///
/// @param wolpiVersion  The version of Wolpi currently running.
/// @param extensionVersion The version of the extension currently running.
/// @param httpClient    A shared [HttpClient] instance that can be used to make HTTP requests from
///                      language runtimes that do not provide a built-in HTTP client.
/// @param logger        A logger instance that can be used to log messages to the Wolpi log.
/// @param config        The configuration object for the extension
/// @param metrics       Can be used to register metrics from extensions.
/// @param vipsArena     An Arena that can be used for allocating native memory for VIPS image
///                      processing in extension code, needed for e.g. overlay images
/// @param imageRequestParser A parser that can be used to parse IIIF image requests, useful for
///                           extensions that want to modify the default image request handling
/// @param baseUri       Optional base URI for the Wolpi instance from the `http.baseUri` setting,
///                      will be determined from the currently active request if not set and request
///                      context is available.
public record ExtensionGuestContext(
        String wolpiVersion,
        String extensionVersion,
        HttpClient httpClient,
        ExtensionLogger logger,
        @Nullable Map<String, Object> config,
        ExtensionMetrics metrics,
        Arena vipsArena,
        ImageRequestParserProxy imageRequestParser,
        @Nullable String baseUri) {

    @Override
    public @Nullable String baseUri() {
        if (this.baseUri != null && !this.baseUri.isBlank()) {
            return this.baseUri;
        }
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
