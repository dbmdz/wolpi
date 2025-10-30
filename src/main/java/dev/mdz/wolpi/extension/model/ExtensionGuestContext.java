package dev.mdz.wolpi.extension.model;

import dev.mdz.wolpi.extension.ExtensionLogger;
import java.net.http.HttpClient;
import org.jspecify.annotations.Nullable;

/// Context object available to extensions at runtime, provides access to metadata, the extension
/// configuration, as well as a shared [HttpClient] instance.
///
/// @param wolpiVersion  The version of Wolpi currently running.
/// @param extensionVersion The version of the extension currently running.
/// @param httpClient    A shared [HttpClient] instance that can be used to make HTTP requests from
///                      language runtimes that do not provide a built-in HTTP client.
/// @param logger        A logger instance that can be used to log messages to the Wolpi log.
/// @param config        The configuration object for the extension, as provided in the Wolpi
///                      configuration. For JavaScript, a [ProxyObject], for Python a [ProxyHashMap]
public record ExtensionGuestContext(
        String wolpiVersion,
        String extensionVersion,
        HttpClient httpClient,
        ExtensionLogger logger,
        @Nullable Object config) {}
