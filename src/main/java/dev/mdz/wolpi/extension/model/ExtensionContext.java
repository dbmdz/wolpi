package dev.mdz.wolpi.extension.model;

import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import java.net.http.HttpClient;
import java.util.Map;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jspecify.annotations.Nullable;

/// Context object available to extensions at runtime, provides access to metadata, the extension
/// configuration, as well as a shared [HttpClient] instance.
///
/// @param wolpiVersion  The version of Wolpi currently running.
/// @param pluginVersion The version of the extension currently running.
/// @param httpClient    A shared [HttpClient] instance that can be used to make HTTP requests from
///                      language runtimes that do not provide a built-in HTTP client.
/// @param config        The configuration object for the extension, as provided in the Wolpi
///                      configuration.
public record ExtensionContext(
    String wolpiVersion,
    String pluginVersion,
    HttpClient httpClient,
    @Nullable Map<String, Object> config) {

  public JSExtensionContext forJS() {
    return new JSExtensionContext(this);
  }

  /// Variant for GraalJS interop, where [Map] objects are not exposed as `object` values.
  public record JSExtensionContext(
      String wolpiVersion,
      String pluginVersion,
      HttpClient httpClient,
      @Nullable ProxyObject config) {

    public JSExtensionContext(ExtensionContext ctx) {
      this(
          ctx.wolpiVersion,
          ctx.pluginVersion,
          ctx.httpClient,
          ctx.config == null ? null : (ProxyObject) PolyglotHelpers.toGuest(ctx.config));
    }
  }
}
