package dev.mdz.iiif.wolpi.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mdz.iiif.wolpi.model.extensions.ExtensionHooks;
import dev.mdz.iiif.wolpi.model.extensions.LoadedExtension;
import dev.mdz.iiif.wolpi.model.extensions.LoadedExtension.RuntimeContext;
import dev.mdz.iiif.wolpi.model.image.CacheInfo;
import dev.mdz.iiif.wolpi.model.image.ImageInfo;
import dev.mdz.iiif.wolpi.model.image.ImageSource;
import dev.mdz.iiif.wolpi.model.image.ResolvedImage;
import dev.mdz.iiif.wolpi.util.PolyglotHelpers;
import java.lang.foreign.Arena;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.pool2.KeyedObjectPool;
import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Request-scoped runtime context extensions are executed in.
///
/// This is what actually executes the extension code during request processing.
/// It exposes methods for each extension hook that Wolpi supports, and calls the appropriate
/// extension functions in the order of their associated extensions in the configuration.
///
/// How multiple extensions interact depends on the hook, see the individual methods for details.
public class ExtensionRuntime {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  // Used for discovery of extensions
  private final ExtensionRegistry registry;

  /// Used for mapping Polyglot [Value]s to Java objects.
  private final ObjectMapper objectMapper;

  // Pool to borrow [RuntimeContext]s for each [LoadedExtension] for
  private final KeyedObjectPool<LoadedExtension, RuntimeContext> contextPool;

  // Keeps track of which [RuntimeContext]s have been borrowed from the pool
  // for which [LoadedExtension]s, so we only return them at the end of the request.
  private final Map<LoadedExtension, RuntimeContext> borrowedContexts = new HashMap<>();

  public ExtensionRuntime(
      ExtensionRegistry registry,
      KeyedObjectPool<LoadedExtension, RuntimeContext> ctxPool,
      ObjectMapper objectMapper) {
    this.registry = registry;
    this.contextPool = ctxPool;
    this.objectMapper = objectMapper;
  }

  /// Borrow a [RuntimeContext] for a [LoadedExtension] from the pool if it doesn't already exist.
  private RuntimeContext ensureRuntimeContext(LoadedExtension ext) {
    return borrowedContexts.computeIfAbsent(
        ext,
        key -> {
          try {
            return contextPool.borrowObject(key);
          } catch (Exception e) {
            throw new RuntimeException(
                "Failed to borrow runtime context for extension: " + key.extensionInfo().name(), e);
          }
        });
  }

  /// Check if the request with the given identifier, headers and client IP is authorized
  /// to access the image with the given identifier.
  ///
  /// If there are multiple extensions implementing this hook, all of them must authorize access
  /// for it to be granted. If any extension denies access, access is denied.
  ///
  /// If no extensions implement the hook, access is allowed by default.
  ///
  /// @param identifier The identifier of the image to authorize access to.
  /// @param headers The HTTP headers of the request, which may contain authorization information.
  /// @param clientIp The IP address of the client making the request, after resolving any
  ///                 proxies.
  /// @return whether access is authorized
  public boolean authorize(String identifier, Map<String, List<String>> headers, String clientIp) {
    List<LoadedExtension> authExts = registry.getExtensions(ExtensionHooks.AUTHORIZE);
    if (authExts.isEmpty()) {
      // Default implementation: Allow all
      return true;
    }

    for (LoadedExtension ext : authExts) {
      RuntimeContext ctx = ensureRuntimeContext(ext);
      Value authorizeFn = PolyglotHelpers.getDictOrObjectMember("authorize", ctx.extensionObject());
      assert authorizeFn != null && authorizeFn.canExecute();
      boolean authorized =
          authorizeFn.execute(identifier, new HashMap<>(headers), clientIp).asBoolean();
      // If any extension denies access, we deny it
      if (!authorized) {
        return false;
      }
    }
    return true;
  }

  /// Resolve an identifier to an [ImageSource] using the registered extensions.
  ///
  /// If multiple extensions implement this hook, they are called in the order they were registered
  /// (i.e. the order in the configuration). The first extension to return a non-null
  /// [ImageSource] wins, and no further extensions are called.
  ///
  /// If no extension returns a non-null [ImageSource], `null` is returned.
  ///
  /// @param identifier the identifier to resolve
  /// @param vipsArena  a [Arena] to use for defining vips-ffm sources
  /// @return the resolved [ImageSource], or `null` if no extension
  public @Nullable ImageSource resolve(String identifier, Arena vipsArena) {
    List<LoadedExtension> resolveExts = registry.getExtensions(ExtensionHooks.RESOLVE);
    if (resolveExts.isEmpty()) {
      return null;
    }

    for (LoadedExtension ext : resolveExts) {
      RuntimeContext ctx = ensureRuntimeContext(ext);
      Value resolveFn = PolyglotHelpers.getDictOrObjectMember("resolve", ctx.extensionObject());
      assert resolveFn != null && resolveFn.canExecute();
      Value source = resolveFn.execute(identifier);
      if (source == null || source.isNull()) {
        continue;
      }

      CacheInfo cacheInfo = null;
      Value cacheInfoValue = PolyglotHelpers.getDictOrObjectMember("cacheInfo", source);
      if (cacheInfoValue != null && !cacheInfoValue.isNull()) {
        cacheInfo = objectMapper.convertValue(cacheInfoValue, CacheInfo.class);
      }

      ImageInfo imageInfo = null;
      Value imageInfoValue = PolyglotHelpers.getDictOrObjectMember("imageInfo", source);
      if (imageInfoValue != null && !imageInfoValue.isNull()) {
        imageInfo = objectMapper.convertValue(imageInfoValue.as(Map.class), ImageInfo.class);
      }

      var img = ResolvedImage.from(vipsArena, source);
      if (img == null) {
        continue;
      }
      return new ImageSource(identifier, img, imageInfo, cacheInfo);
    }
    return null;
  }

  /// Close this [ExtensionRuntime], returning all borrowed [RuntimeContext]s to the pool.
  public void close() {
    for (var entry : this.borrowedContexts.entrySet()) {
      try {
        this.contextPool.returnObject(entry.getKey(), entry.getValue());
      } catch (Exception e) {
        log.error(
            "Failed to return runtime context for extension: {}",
            entry.getKey().extensionInfo().name(),
            e);
      }
    }
  }
}
