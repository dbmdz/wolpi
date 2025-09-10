package dev.mdz.wolpi.extension;

import app.photofox.vipsffm.VCustomSource;
import app.photofox.vipsffm.VCustomSource.ReadCallback;
import app.photofox.vipsffm.VCustomSource.SeekCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mdz.wolpi.image.exceptions.SourceNotModifiedException;
import dev.mdz.wolpi.extension.model.ExtensionHooks;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.extension.model.LoadedExtension.RuntimeContext;
import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import dev.mdz.wolpi.model.BinaryResolvedImage;
import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.CustomSourceResolvedImage;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSource;
import dev.mdz.wolpi.model.ResolvedImage;
import java.lang.foreign.Arena;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
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
  /// @param eTag       optional ETag of a cached image
  /// @param lastModified optional last modified timestamp of a cached image
  /// @param vipsArena  a [Arena] to use for defining vips-ffm sources
  /// @return the resolved [ImageSource], or `null` if no extension
  public @Nullable ImageSource resolve(
      String identifier, @Nullable String eTag, @Nullable Instant lastModified, Arena vipsArena)
      throws SourceNotModifiedException {
    List<LoadedExtension> resolveExts = registry.getExtensions(ExtensionHooks.RESOLVE);
    if (resolveExts.isEmpty()) {
      return null;
    }

    String lastModifiedStr =
        lastModified != null
            ? DateTimeFormatter.RFC_1123_DATE_TIME.format(lastModified.atZone(ZoneOffset.UTC))
            : null;
    for (LoadedExtension ext : resolveExts) {
      RuntimeContext ctx = ensureRuntimeContext(ext);
      Value resolveFn = PolyglotHelpers.getDictOrObjectMember("resolve", ctx.extensionObject());
      assert resolveFn != null && resolveFn.canExecute();
      Value source = resolveFn.execute(identifier, eTag, lastModifiedStr);
      if (source == null || source.isNull()) {
        continue;
      }

      Value notModifiedValue = PolyglotHelpers.getDictOrObjectMember("notModified", source);
      if (notModifiedValue != null && !notModifiedValue.isNull() && notModifiedValue.asBoolean()) {
        // If the extension indicates that the source has not been modified, we throw
        // SourceNotModifiedException to short-circuit further processing.
        throw new SourceNotModifiedException();
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

      var img = imageFromResolveHook(vipsArena, source);
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

  ///  Given the return value of a `resolve` hook, convert it to a [ResolvedImage].
  ///
  /// @param vipsArena a [Arena] to use for defining custom vips-ffm sources
  /// @param polyglotValue the value returned by the `resolve` hook
  /// @return the corresponding [ResolvedImage], or `null` if the value is invalid
  static @Nullable ResolvedImage imageFromResolveHook(
      Arena vipsArena, @Nullable Value polyglotValue) {
    if (polyglotValue == null) {
      return null;
    }

    if (PolyglotHelpers.hasDictOrObjectMember("url", polyglotValue)) {
      var url = PolyglotHelpers.getDictOrObjectMember("url", polyglotValue);
      assert url != null && !url.isNull();
      var supportsByteRanges =
          PolyglotHelpers.getDictOrObjectMember("supportsByteRanges", polyglotValue);

      //noinspection unchecked
      return new HttpResolvedImage(
          URI.create(url.asString()),
          PolyglotHelpers.hasDictOrObjectMember("headers", polyglotValue)
              ? Collections.unmodifiableMap(
                  (Map<String, String>)
                      PolyglotHelpers.getDictOrObjectMember("headers", polyglotValue).as(Map.class))
              : null,
          supportsByteRanges != null && !supportsByteRanges.isNull()
              ? supportsByteRanges.asBoolean()
              : null);
    } else if (PolyglotHelpers.hasDictOrObjectMember("rawData", polyglotValue)
        && PolyglotHelpers.hasDictOrObjectMember("mimeType", polyglotValue)) {
      return new BinaryResolvedImage(
          PolyglotHelpers.getDictOrObjectMember("rawData", polyglotValue).as(byte[].class));
    } else if (PolyglotHelpers.hasDictOrObjectMember("onRead", polyglotValue)
        && PolyglotHelpers.hasDictOrObjectMember("onWrite", polyglotValue)) {
      ReadCallback readCb =
          (memorySegment, length) -> {
            // TODO: Call read on the polyglot value
            // TODO: Write returned data to the memory segment
            throw new UnsupportedOperationException();
          };
      SeekCallback seekCb =
          (offset, whence) -> {
            // TODO: Call seek on the polyglot value
            throw new UnsupportedOperationException();
          };
      return new CustomSourceResolvedImage(new VCustomSource(vipsArena, readCb, seekCb));
    } else if (PolyglotHelpers.hasDictOrObjectMember("path", polyglotValue)) {
      var pathValue = PolyglotHelpers.getDictOrObjectMember("path", polyglotValue);
      assert pathValue != null;
      return new FilesystemResolvedImage(Path.of(pathValue.asString()));
    } else {
      return null;
    }
  }
}
