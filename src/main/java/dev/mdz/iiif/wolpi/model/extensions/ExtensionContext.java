package dev.mdz.iiif.wolpi.model.extensions;

import java.net.http.HttpClient;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// Context object available to extensions at runtime, provides access to
/// metadata, the extension configuration, as well as a shared HttpClient instance.
///
/// @param wolpiVersion The version of Wolpi currently running.
/// @param pluginVersion The version of the extension currently running.
/// @param httpClient A shared [HttpClient] instance that can be used to make HTTP requests
///                   from language runtimes that do not provide a built-in HTTP client.
/// @param config The configuration object for the extension, as provided in the Wolpi configuration.
public record ExtensionContext(
    String wolpiVersion,
    String pluginVersion,
    HttpClient httpClient,
    @Nullable Map<String, Object> config) {}
