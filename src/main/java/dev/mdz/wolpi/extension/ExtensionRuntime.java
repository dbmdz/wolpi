package dev.mdz.wolpi.extension;

import app.photofox.vipsffm.VImage;
import dev.mdz.wolpi.extension.model.ExtensionHooks;
import dev.mdz.wolpi.extension.model.Language;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.iiif.model.ImageRequest;
import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.EncodedImage;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSource;
import dev.mdz.wolpi.model.ResolvedImage;
import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.apache.commons.pool2.KeyedObjectPool;
import org.graalvm.polyglot.TypeLiteral;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Request-scoped runtime context extensions are executed in.
///
/// This is what actually executes the extension code during request processing. It exposes methods
/// for each extension hook that Wolpi supports, and calls the appropriate extension functions in
/// the order of their associated extensions in the configuration.
///
/// How multiple extensions interact depends on the hook, see the individual methods for details.
///
/// Split into interface and implementation so that we can have a request-scoped bean for the
/// component without having to rely on CGLIB proxies, which don't work well with the Java module
/// system.
public interface ExtensionRuntime extends AutoCloseable {
    /// Check if there are any extensions registered for the given hook.
    boolean hasExtensionsForHook(ExtensionHooks extensionHooks);

    /// Check if the request with the given identifier, headers and client IP is authorized to access
    /// the image with the given identifier.
    ///
    /// If there are multiple extensions implementing this hook, all of them must authorize access for
    /// it to be granted. If any extension denies access, access is denied.
    ///
    /// If no extensions implement the hook, access is allowed by default.
    ///
    /// @param identifier The identifier of the image to authorize access to.
    /// @param headers    The HTTP headers of the request, which may contain authorization
    ///                   information.
    /// @param clientIp   The IP address of the client making the request, after resolving any
    ///                   proxies.
    /// @return whether access is authorized
    boolean authorize(String identifier, Map<String, List<String>> headers, String clientIp);

    /// Resolve an identifier to an [ImageSource] using the registered extensions.
    ///
    /// If multiple extensions implement this hook, they are called in the order they were registered
    /// (i.e. the order in the configuration). The first extension to return a non-null [ImageSource]
    /// wins, and no further extensions are called.
    ///
    /// If no extension returns a non-null [ImageSource], `null` is returned.
    ///
    /// @param identifier   the identifier to resolve
    /// @param eTag         optional ETag of a cached image
    /// @param lastModified optional last modified timestamp of a cached image
    /// @return the resolved [ImageSource], or `null` if no extension
    @Nullable ImageSource resolve(String identifier, @Nullable String eTag, @Nullable Instant lastModified);

    /// Allow extensions to augment the standard info.json data for an identifier.
    ///
    /// If multiple extensions implement this hook, they are called in the order they were registered
    /// (i.e. the order in the configuration). Each extension receives the result of the
    /// previous extension (or the standard info.json data if no previous extension augmented it)
    /// and may return a modified version of it. If an extension returns `null`, the
    /// result of the previous extension is passed to the next one.
    ///
    /// If no extension returns a non-null value, `null` is returned.
    ///
    /// @param identifier        the identifier of the image
    /// @param standardInfoJson  the standard info.json data as a map
    /// @param version           the IIIF version being requested, determines if the input
    ///                          data is in v2 or v3 format
    /// @return the augmented info.json data, or `null` if no extension modified it
    @Nullable Map<String, Object> augmentInfoJson(String identifier, Map<String, Object> standardInfoJson, IIIFVersion version);

    /// Allow extensions to pre-process an image before further processing.
    ///
    /// The image returned from this hook must have the same dimensions as the input image.
    ///
    /// If multiple extensions implement this hook, they are called in the order they were registered
    /// (i.e. the order in the configuration). Each extension receives the image as modified
    /// by the previous extension (or the original image if no previous extension modified it)
    /// and may return a modified version of it. The result from the last extension is returned.
    ///
    /// If no extensions are registered for this hook, `null` is returned.
    ///
    /// @param image       the image to pre-process
    /// @param identifier  the identifier of the image
    /// @param info        the image info metadata based on the original input image
    /// @param request     the image request parameters
    /// @return the pre-processed image or `null` if no processing was done
    @Nullable VImage preProcessImage(VImage image, String identifier, ImageInfo info, ImageRequest request);

    /// Allow extensions to scale an image.
    ///
    /// If multiple extensions implement this hook, they are called in the order they were registered
    /// (i.e. the order in the configuration). The first extension to return a non-null [VImage]
    ///  wins, and no further extensions are called.
    ///
    /// If no extensions are registered for this hook, `null` is returned.
    ///
    /// @param image       the image to scale
    /// @param identifier  the identifier of the image
    /// @param info        the image info metadata based on the original input image
    /// @param request     the image request parameters
    /// @return the scaled image or `null` if no scaling was done
    @Nullable VImage preScale(VImage image, String identifier, ImageInfo info, ImageRequest request);

    /// Allow extensions to crop an image.
    ///
    /// If multiple extensions implement this hook, they are called in the order they were registered
    /// (i.e. the order in the configuration). The first extension to return a non-null [VImage]
    ///  wins, and no further extensions are called.
    ///
    /// If no extensions are registered for this hook, `null` is returned.
    ///
    /// @param image       the image to crop
    /// @param identifier  the identifier of the image
    /// @param info        the image info metadata based on the original input image
    /// @param request     the image request parameters
    /// @return the cropped image or `null` if no cropping was done
    @Nullable VImage preCrop(VImage image, String identifier, ImageInfo info, ImageRequest request);

    /// Allow extensions to customize the encoding of the processed image.
    ///
    /// If multiple extensions implement this hook, they are called in the order they were registered
    /// (i.e. the order in the configuration). Each extension receives the image as processed
    /// so far, and may return an [EncodedImage] indicating whether it encoded
    /// the image or not. The first extension to indicate that it encoded the image
    /// wins, and no further extensions are called.
    ///
    /// If no extension encodes the image, `null` is returned.
    ///
    /// @param image          the image to encode
    /// @param identifier     the identifier of the image
    /// @param info           the image info metadata based on the original input image
    /// @param request        the image request parameters
    /// @return an [EncodedImage] if an extension encoded the image, or `null` otherwise
    @Nullable EncodedImage preFormat(VImage image, String identifier, ImageInfo info, ImageRequest request);

    @Override
    void close();

    class ExtensionRuntimeImpl implements ExtensionRuntime, AutoCloseable {
        static final TypeLiteral<Map<String, Object>> InfoJson = new TypeLiteral<>() {};

        private static final Logger log =
                LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

        // Used for discovery of extensions
        private final ExtensionRegistry registry;

        /// Pool to borrow [RuntimeContext]s for each [LoadedExtension] for
        private final KeyedObjectPool<LoadedExtension, RuntimeContext> contextPool;

        /// Keeps track of which [RuntimeContext]s have been borrowed from the pool
        /// for which [LoadedExtension]s, so we only return them at the end of the request.
        private final Map<LoadedExtension, RuntimeContext> borrowedContexts = new ConcurrentHashMap<>();

        /// Thread pool to use for executing extension code in parallel, used to speed up
        /// auth and resolving if multiple extensions are configured.
        private final ExecutorService threadPool;

        public ExtensionRuntimeImpl(
                ExtensionRegistry registry,
                KeyedObjectPool<LoadedExtension, RuntimeContext> ctxPool,
                ExecutorService threadPool) {
            this.registry = registry;
            this.contextPool = ctxPool;
            this.threadPool = threadPool;
        }

        /// Borrow a [RuntimeContext] for a [LoadedExtension] from the pool if it doesn't already exist.
        private RuntimeContext ensureRuntimeContext(LoadedExtension ext) {
            return borrowedContexts.computeIfAbsent(ext, key -> {
                try {
                    return contextPool.borrowObject(key);
                } catch (Exception e) {
                    throw new RuntimeException(
                            "Failed to borrow runtime context for extension: "
                                    + key.extensionInfo().name(),
                            e);
                }
            });
        }

        @Override
        public boolean hasExtensionsForHook(ExtensionHooks hook) {
            return !registry.getExtensions(hook).isEmpty();
        }

        @Override
        public boolean authorize(String identifier, Map<String, List<String>> headers, String clientIp) {
            List<LoadedExtension> authExts = registry.getExtensions(ExtensionHooks.AUTHORIZE);
            if (authExts.isEmpty()) {
                // Default implementation: Allow all
                return true;
            }

            // No need for parallelism if there's only one extension
            if (authExts.size() == 1) {
                var ctx = ensureRuntimeContext(authExts.get(0));
                try (var lease = ctx.enter()) {
                    return runAuthExtension(ctx.getLang(), lease.extension(), identifier, headers, clientIp);
                }
            }

            // Multiple extensions: All must authorize access, run in parallel and cancel on first failure
            var completionService = new ExecutorCompletionService<Boolean>(threadPool);
            var futs = authExts.stream()
                    .map(e -> completionService.submit(() -> {
                        RuntimeContext ctx = ensureRuntimeContext(e);
                        return ctx.run(ext -> runAuthExtension(ctx.getLang(), ext, identifier, headers, clientIp));
                    }))
                    .toList();
            boolean authorized = false;
            int completed = 0;
            while (completed < authExts.size()) {
                try {
                    var future = completionService.poll(60, java.util.concurrent.TimeUnit.SECONDS);
                    if (future == null) {
                        log.warn("Timeout waiting for extension authorization, denying access");
                        authorized = false;
                        break;
                    }
                    authorized = future.get();
                    completed++;
                    if (!authorized) {
                        // One extension denied access, no need to wait for the others
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log.error("Error while running extension authorization, denying access", e);
                    authorized = false;
                    break;
                }
            }

            // Cancel all remaining futures if we didn't complete them all
            futs.stream().filter(f -> !f.isDone()).forEach(f -> f.cancel(true));

            return authorized;
        }

        private boolean runAuthExtension(
                Language lang, Value ext, String identifier, Map<String, List<String>> headers, String clientIp) {
            Value authorizeFn = PolyglotHelpers.getDictOrObjectMember("authorize", ext);
            assert authorizeFn != null && authorizeFn.canExecute();
            return switch (lang) {
                case PYTHON ->
                    authorizeFn.execute(identifier, headers, clientIp).asBoolean();
                // JS needs a ProxyObject to be able to access the headers as a proper JS object
                case JAVASCRIPT ->
                    authorizeFn
                            .execute(identifier, ProxyObject.fromMap(new HashMap<>(headers)), clientIp)
                            .asBoolean();
            };
        }

        @Override
        public @Nullable ImageSource resolve(String identifier, @Nullable String eTag, @Nullable Instant lastModified) {
            List<LoadedExtension> resolveExts = registry.getExtensions(ExtensionHooks.RESOLVE);
            if (resolveExts.isEmpty()) {
                return null;
            }

            String lastModifiedStr = lastModified != null
                    ? DateTimeFormatter.RFC_1123_DATE_TIME.format(lastModified.atZone(ZoneOffset.UTC))
                    : null;

            // No need for parallelism if there's only one extension
            if (resolveExts.size() == 1) {
                RuntimeContext ctx = ensureRuntimeContext(resolveExts.get(0));
                try (var lease = ctx.enter()) {
                    return runResolvingExtension(lease.extension(), identifier, eTag, lastModifiedStr);
                }
            }

            // Run the resolving extensions in parallel and return the first valid result
            var completionService = new ExecutorCompletionService<@Nullable ImageSource>(threadPool);
            List<Future<ImageSource>> futures = new ArrayList<>();
            for (LoadedExtension resolveExt : resolveExts) {
                futures.add(completionService.submit(() -> {
                    RuntimeContext ctx = ensureRuntimeContext(resolveExt);
                    try (var lease = ctx.enter()) {
                        return runResolvingExtension(lease.extension(), identifier, eTag, lastModifiedStr);
                    }
                }));
            }

            ImageSource resolved = null;
            int completed = 0;
            while (completed < resolveExts.size()) {
                try {
                    var fut = completionService.poll(60, java.util.concurrent.TimeUnit.SECONDS);
                    if (fut == null) {
                        log.warn("Timeout waiting for extension resolving, returning null");
                        break;
                    }
                    resolved = fut.get();
                    completed++;
                    if (resolved != null) {
                        // One extension resolved the source, no need to wait for the others
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log.error("Error while running resolving hook, ignoring.", e);
                    completed++;
                }
            }

            // Cancel all remaining futures if we didn't complete them all
            futures.stream().filter(f -> !f.isDone()).forEach(f -> f.cancel(true));

            return resolved;
        }

        private @Nullable ImageSource runResolvingExtension(
                Value extObj, String identifier, @Nullable String eTag, @Nullable String lastModifiedStr) {
            Value resolveFn = PolyglotHelpers.getDictOrObjectMember("resolve", extObj);
            assert resolveFn != null && resolveFn.canExecute();
            Value source = resolveFn.execute(identifier, eTag, lastModifiedStr);
            if (source == null || source.isNull()) {
                return null;
            }

            CacheInfo cacheInfo = null;
            Value cacheInfoValue = PolyglotHelpers.getDictOrObjectMember("cacheInfo", source, true);
            if (cacheInfoValue != null && !cacheInfoValue.isNull()) {
                cacheInfo = cacheInfoValue.as(CacheInfo.class);
            }

            ImageInfo imageInfo = null;
            Value imageInfoValue = PolyglotHelpers.getDictOrObjectMember("imageInfo", source, true);
            if (imageInfoValue != null && !imageInfoValue.isNull()) {
                imageInfo = imageInfoValue.as(ImageInfo.class);
            }

            try {
                var img = source.as(ResolvedImage.class);
                return new ImageSource(identifier, img, imageInfo, cacheInfo);
            } catch (ClassCastException e) {
                log.warn(
                        "Extension returned invalid value {} from resolve hook, cannot convert to ResolvedImage",
                        source,
                        e);
                return null;
            }
        }

        public @Nullable Map<String, Object> augmentInfoJson(
                String identifier, Map<String, Object> standardInfoJson, IIIFVersion iiifVersion) {
            List<LoadedExtension> infoJsonExts = registry.getExtensions(ExtensionHooks.INFO_JSON);
            Map<String, Object> augmented = null;
            for (LoadedExtension ext : infoJsonExts) {
                RuntimeContext ctx = ensureRuntimeContext(ext);
                var rv = ctx.runHook(
                        ExtensionHooks.INFO_JSON,
                        identifier,
                        PolyglotHelpers.toGuest(augmented != null ? augmented : standardInfoJson, ctx.getLang()),
                        iiifVersion.value());
                if (rv != null && !rv.isNull()) {
                    augmented = rv.as(InfoJson);
                }
            }
            return (Map<String, Object>) PolyglotHelpers.toHost(augmented);
        }

        @Override
        @Nullable public VImage preProcessImage(VImage image, String identifier, ImageInfo info, ImageRequest request) {
            List<LoadedExtension> exts = registry.getExtensions(ExtensionHooks.PREPROCESS_IMAGE);
            VImage processed = null;
            for (LoadedExtension ext : exts) {
                RuntimeContext ctx = ensureRuntimeContext(ext);
                var rv = ctx.runHook(
                        ExtensionHooks.PREPROCESS_IMAGE,
                        processed == null ? image : processed,
                        identifier,
                        info,
                        request);
                if (rv == null || rv.isNull()) {
                    continue;
                }
                VImage tmp = rv.as(VImage.class);
                if (tmp.getWidth() != image.getWidth() || tmp.getHeight() != image.getHeight()) {
                    log.warn(
                            "Preprocessing hook in extension {} returned image with different dimensions ({}x{}) than input image ({}x{}), ignoring result.",
                            ext.extensionInfo().name(),
                            tmp.getWidth(),
                            tmp.getHeight(),
                            image.getWidth(),
                            image.getHeight());
                    continue;
                }
                processed = tmp;
            }
            return processed;
        }

        @Override
        public @Nullable VImage preScale(VImage image, String identifier, ImageInfo info, ImageRequest request) {
            return runHookWithEarlyExit(ExtensionHooks.SCALE, image, identifier, info, request);
        }

        @Override
        public @Nullable VImage preCrop(VImage image, String identifier, ImageInfo info, ImageRequest request) {
            return runHookWithEarlyExit(ExtensionHooks.CROP, image, identifier, info, request);
        }

        private @Nullable VImage runHookWithEarlyExit(
                ExtensionHooks hook, VImage image, String identifier, ImageInfo info, ImageRequest request) {
            List<LoadedExtension> exts = registry.getExtensions(hook);
            for (LoadedExtension ext : exts) {
                RuntimeContext ctx = ensureRuntimeContext(ext);
                var rv = ctx.runHook(hook, image, identifier, info, request);
                if (rv != null && !rv.isNull()) {
                    return rv.as(VImage.class);
                }
            }
            return null;
        }

        @Override
        public @Nullable EncodedImage preFormat(VImage image, String identifier, ImageInfo info, ImageRequest request) {
            List<LoadedExtension> formatExts = registry.getExtensions(ExtensionHooks.FORMAT);
            for (LoadedExtension ext : formatExts) {
                RuntimeContext ctx = ensureRuntimeContext(ext);
                var rv = ctx.runHook(ExtensionHooks.FORMAT, image, identifier, info, request);
                if (rv != null && !rv.isNull()) {
                    return rv.as(EncodedImage.class);
                }
            }
            return null;
        }

        /// Close this [ExtensionRuntimeImpl], returning all borrowed [RuntimeContext]s to the pool.
        @Override
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
}
