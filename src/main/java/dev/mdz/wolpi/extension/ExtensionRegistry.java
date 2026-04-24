package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.config.ExtensionConfig;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.PyPiInstaller.EntryPoint;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.extension.model.ExtensionHooks;
import dev.mdz.wolpi.extension.model.ExtensionInfo;
import dev.mdz.wolpi.extension.model.JSLoadedExtension;
import dev.mdz.wolpi.extension.model.Language;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.extension.model.PythonLoadedExtension;
import dev.mdz.wolpi.extension.util.FileAlterationMonitor;
import dev.mdz.wolpi.extension.util.FileAlterationMonitor.AlterationEvent;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.graalvm.polyglot.Source;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// Responsible for loading and initializing extensions.
///
/// Currently, we support extensions written in JavaScript/ECMAScript (ESM) and Python.
///
/// For JavaScript extensions, these requirements apply:
/// - Must be a single .js file, a directory containing a `package.json` or a package from npm.
/// - Must export an object with functions corresponding to the supported hooks.
/// - For packages, they must provide an `exports` key in the `package.json` that points to the
/// entry point that exports the hooks.
///
/// For Python extensions, these requirements apply:
/// - Must be a single .py file, a directory containing a Python package or a package from PyPI.
/// - Single Files must either:
///   - Define the hooks as top-level functions in the file.
///   - Define a `wolpi_extension()` function that returns an object with the hooks as methods.
/// - Packages must define a `wolpi-ext` entry point in their `pyproject.toml` or `setup.py` that
/// points to a callable that returns an object with the hooks as methods.
@Component
public class ExtensionRegistry implements AutoCloseable {
    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// Map of extension configurations to their most recent loaded instances
    private final Map<ExtensionConfig, LoadedExtension> loadedExtensions = new ConcurrentHashMap<>();

    /// Map of hooks to the list of extensions that implement them
    private final Map<ExtensionHooks, List<LoadedExtension>> implementedHooks;

    /// Non-empty when running validation tests against extensions in isolation,
    /// contains all extensions except the one that is under test.
    private final Set<LoadedExtension> disabledExtensions = new HashSet<>();

    /// Callbacks for reloads of extensions when their source files change.
    private final Map<ExtensionConfig, List<Consumer<LoadedExtension>>> reloadCallbacks = new ConcurrentHashMap<>();

    private final GraalContextSupplier contextSupplier;
    private final PyPiInstaller pyInstaller;
    private final NpmInstaller jsInstaller;
    private final @Nullable FileAlterationMonitor fileMonitor;
    private final GenericKeyedObjectPool<LoadedExtension, RuntimeContext> extensionContextPool;
    private final GuestContextFactory guestContextFactory;
    private final WolpiConfig wolpiConfig;

    public ExtensionRegistry(
            WolpiConfig cfg,
            PyPiInstaller pyInstaller,
            NpmInstaller jsInstaller,
            GenericKeyedObjectPool<LoadedExtension, RuntimeContext> extensionContextPool,
            GraalContextSupplier contextSupplier,
            GuestContextFactory guestContextFactory) {
        this.pyInstaller = pyInstaller;
        this.jsInstaller = jsInstaller;
        this.extensionContextPool = extensionContextPool;
        this.contextSupplier = contextSupplier;
        this.guestContextFactory = guestContextFactory;
        this.wolpiConfig = cfg;

        FileAlterationMonitor monitor;
        try {
            monitor = new FileAlterationMonitor();
            monitor.start();
        } catch (IOException e) {
            log.warn("Failed to initialize file monitoring, live-reloading of extensions disabled.", e);
            monitor = null;
        }
        this.fileMonitor = monitor;

        this.implementedHooks = new ConcurrentHashMap<>();
        if (cfg.extensions().isEmpty()) {
            return;
        }
        // Parallelize extension loading to speed up application startup time
        Set<ExtensionConfig> failedExtensions = new HashSet<>();
        try (ExecutorService pool =
                Executors.newFixedThreadPool(cfg.extensions().size())) {
            List<Callable<LoadedExtension>> loadingTasks = cfg.extensions().stream()
                    .<Callable<LoadedExtension>>map(ext -> () -> {
                        LoadedExtension loaded = null;
                        try {
                            loaded = loadExtension(ext);
                            log.info(
                                    "Extension '{}' loaded.",
                                    loaded.extensionInfo().name());
                        } catch (ExtensionLoadException e) {
                            log.error("Failed to load extension from {}: {}", ext, e.getMessage());
                            log.debug("Error details:", e);
                        } catch (RuntimeException e) {
                            log.error("Unexpected error while loading extension from " + ext, e);
                        }
                        if (loaded == null) {
                            failedExtensions.add(ext);
                        }
                        return loaded;
                    })
                    .toList();
            pool.invokeAll(loadingTasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Load an extension based on its definition in the provided configuration.
    ///
    /// If an earlier version of the extension with the same name is already loaded, it is
    /// replaced with the new version.
    ///
    /// @param config extension definition from the Wolpi configuration
    /// @return the loaded extension
    /// @throws ExtensionLoadException if loading the extension fails
    public LoadedExtension loadExtension(ExtensionConfig config) throws ExtensionLoadException {
        return this.loadExtension(config, null);
    }

    /// Load an extension based on its definition in the provided configuration.
    ///
    /// If an earlier version of the extension with the same name is already loaded, it is
    /// replaced with the new version.
    ///
    /// @param config extension definition from the Wolpi configuration
    /// @param lastModified optional last modified timestamp of the extension source, used to
    ///                     determine if a reload is necessary when live-reloading is enabled.
    ///                     Should only be set if the load is performed as part of a live-reload
    ///                     operation, otherwise it should be null.
    /// @return the loaded extension
    /// @throws ExtensionLoadException if loading the extension fails
    private LoadedExtension loadExtension(ExtensionConfig config, @Nullable Instant lastModified)
            throws ExtensionLoadException {
        if (loadedExtensions.containsKey(config)) {
            var loadedExt = loadedExtensions.get(config);
            var lastModifiedExt = loadedExt.lastModified();
            if (!config.liveReload()
                    || lastModified == null
                    || (lastModifiedExt != null && lastModifiedExt.isAfter(lastModified))) {
                log.debug("Extension from {} is already loaded and up to date, skipping reload.", config);
                return loadedExt;
            }
        }

        LoadedExtension ext;
        if (config.path() != null) {
            Path path = config.path().toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                throw new ExtensionLoadException("Extension path does not exist: " + path);
            }
            if (!Files.isReadable(path)) {
                throw new ExtensionLoadException("Extension path is not readable: " + path);
            }
            if (path.toString().endsWith(".js") || path.toString().endsWith(".mjs")) {
                ext = loadJsExtension(config, lastModified);
            } else if (path.toString().endsWith(".py")) {
                ext = loadPythonExtension(config, lastModified);
            } else if (!Files.isDirectory(path)) {
                throw new ExtensionLoadException("Extension path must be a directory or a .js/.py file: " + path);
            } else if (Files.exists(path.resolve("pyproject.toml"))) {
                ext = loadPythonExtension(config, lastModified);
            } else if (Files.exists(path.resolve("package.json"))) {
                ext = loadJsExtension(config, lastModified);
            } else {
                throw new ExtensionLoadException(
                        "Extension path must contain a package.json (for JS) or pyproject.toml (for Python): " + path);
            }
        } else if (config.npm() != null) {
            ext = loadJsExtension(config, null);
        } else if (config.pypi() != null) {
            ext = loadPythonExtension(config, null);
        } else {
            throw new IllegalArgumentException("Invalid extension configuration.");
        }

        // Set up live-reloading if enabled and supported, and we have the initial load (i.e. not a
        // timestamp from a reload)
        if (config.liveReload() && fileMonitor != null && lastModified == null) {
            if (config.path() == null) {
                log.warn(
                        "{}: Live reloading can only be enabled for local extensions, setting will be ignored.",
                        ext.extensionInfo().name());
            } else {
                var extName = ext.extensionInfo().name();
                Predicate<Path> liveReloadFilter =
                        switch (ext) {
                            case JSLoadedExtension _ ->
                                path -> path.toString().endsWith(".js")
                                        || path.toString().endsWith(".mjs");
                            case PythonLoadedExtension _ ->
                                path -> path.toString().endsWith(".py");
                            default -> path -> true;
                        };
                try {
                    fileMonitor.monitor(config.path(), evt -> this.onReload(extName, config, evt), liveReloadFilter);
                } catch (IOException e) {
                    log.warn(
                            "Failed to set up live-reloading for extension {}, disabling live-reload for it.",
                            extName,
                            e);
                }
            }
        }

        // First, remove the extension from all hooks to avoid duplication with older versions
        for (List<LoadedExtension> exts : implementedHooks.values()) {
            exts.stream()
                    .filter(e -> e.config().equals(ext.config()))
                    .findFirst()
                    .ifPresent(exts::remove);
        }
        // Then add it
        loadedExtensions.put(config, ext);
        for (ExtensionHooks hook : ext.implementedHooks()) {
            implementedHooks
                    .computeIfAbsent(hook, (k) -> new CopyOnWriteArrayList<>())
                    .add(ext);
        }
        return ext;
    }

    /// Determine the set of extension hooks implemented by a given extension.
    ///
    /// Also verifies that the mandatory `info` and `cleanup` hooks are implemented.
    ///
    /// @param ctx a runtime context associated with the extension
    /// @return the set of hooks implemented by the extension
    /// @throws ExtensionLoadException if the mandatory hooks are not implemented
    private Set<ExtensionHooks> getExtensionHooks(RuntimeContext ctx) throws ExtensionLoadException {
        Set<ExtensionHooks> providedHooks =
                Arrays.stream(ExtensionHooks.values()).filter(ctx::hasHook).collect(Collectors.toSet());
        if (!providedHooks.contains(ExtensionHooks.INFO)) {
            throw new ExtensionLoadException("Extension does not provide the mandatory 'info' hook, cannot load.");
        }
        if (!providedHooks.contains(ExtensionHooks.CLEANUP)) {
            throw new ExtensionLoadException("Extension does not provide the mandatory 'cleanup' hook, cannot load.");
        }
        return providedHooks;
    }

    /// Load a Python extension based on its definition in the configuration.
    ///
    /// @param config extension definition from the Wolpi configurations
    /// @param lastModified optional last modified timestamp of the extension source, used to
    ///                     determine if a reload is necessary when live-reloading is enabled.
    ///                     Should only be set if the load is performed as part of a live-reload
    ///                     operation, otherwise it should be null.
    /// @return the loaded extension
    /// @throws ExtensionLoadException if loading the extension fails
    private LoadedExtension loadPythonExtension(ExtensionConfig config, @Nullable Instant lastModified)
            throws ExtensionLoadException {
        boolean isLocalFile = config.path() != null && Files.isRegularFile(config.path());
        boolean isLocalPackage = config.path() != null && Files.isDirectory(config.path());
        boolean isPyPi = config.pypi() != null;

        String packageName;
        String extensionVersion = null;
        final Source source;
        EntryPoint entryPoint;
        Path sitePackagesPath;

        try {
            if (isLocalPackage) {
                packageName = pyInstaller.installExtensionFromLocalDirectory(config.path(), config.liveReload());
                sitePackagesPath = pyInstaller.getVenvSitePackages(packageName);
                if (sitePackagesPath == null) {
                    throw new ExtensionLoadException(
                            "Could not find virtual environment for installed package: " + packageName);
                }
                entryPoint = pyInstaller.getWolpiEntryPoint(packageName);
                extensionVersion = pyInstaller.getVersion(packageName);
            } else if (isPyPi) {
                packageName = config.pypi().pkg();
                pyInstaller.installExtension(
                        config.pypi().pkg(),
                        config.pypi().version(),
                        config.pypi().index(),
                        config.pypi().indexAuth());
                sitePackagesPath = pyInstaller.getVenvSitePackages(config.pypi().pkg());
                if (sitePackagesPath == null) {
                    throw new ExtensionLoadException("Could not find virtual environment for installed package: "
                            + config.pypi().pkg());
                }
                entryPoint = pyInstaller.getWolpiEntryPoint(config.pypi().pkg());
                extensionVersion = config.pypi().version();
            } else if (isLocalFile) {
                packageName = config.path().getFileName().toString().split("\\.")[0];
                entryPoint = null;
                sitePackagesPath = null;
            } else {
                throw new IllegalArgumentException("Invalid Python extension configuration.");
            }
        } catch (PackageInstallException e) {
            throw new ExtensionLoadException(
                    "Failed to install Python extension package: %s".formatted(e.getMessage()), e);
        }

        Path venvPath;
        try {
            if (entryPoint != null) {
                // For packages, we have to import the function returning the implementation
                // from the entrypoint module.
                venvPath = sitePackagesPath.getParent().getParent().getParent();
                String code;
                if (config.liveReload()) {
                    // When live-reloading is enabled, we have to reload the module/invalidate
                    // the module cache to pick up changes.
                    code = """
                            import importlib
                            import sys

                            if '%s' in sys.modules:
                                importlib.reload(sys.modules['%s'])

                            from %s import %s
                            """.formatted(
                            entryPoint.module(), entryPoint.module(), entryPoint.module(), entryPoint.function());
                } else {
                    code = "from %s import %s\n".formatted(entryPoint.module(), entryPoint.function());
                }
                source = Source.newBuilder("python", code, "%s.py".formatted(packageName))
                        .build();
            } else {
                // Simple python files can be loaded directly.
                venvPath = null;
                assert config.path() != null;
                var sourceBuilder = Source.newBuilder("python", config.path().toFile());
                if (lastModified != null) {
                    // GraalPy may reuse a cached Source for the same file URI. During live reloads
                    // the path is stable but the contents changed, so force the new file contents
                    // to be parsed instead of reusing the previous top-level hook bindings.
                    sourceBuilder.cached(false);
                }
                source = sourceBuilder.build();
            }
        } catch (IOException e) {
            throw new ExtensionLoadException("Failed to load Python extension source", e);
        }

        if (extensionVersion == null) {
            extensionVersion = "unknown";
        }

        String baseUri = null;
        if (wolpiConfig != null && wolpiConfig.http() != null) {
            var configuredBaseUri = wolpiConfig.http().baseUri();
            if (configuredBaseUri != null && !configuredBaseUri.isBlank()) {
                baseUri = configuredBaseUri;
            }
        }
        var guestCtx = guestContextFactory.createGuestContext(
                packageName, extensionVersion, config.config(), Language.PYTHON, baseUri);
        try (RuntimeContext ctx = new PythonRuntimeContext(source, entryPoint, venvPath, guestCtx, contextSupplier)) {
            var hooks = getExtensionHooks(ctx);
            var info = ctx.runHook(ExtensionHooks.INFO).as(ExtensionInfo.class);
            return new PythonLoadedExtension(
                    config, source, info, extensionVersion, hooks, entryPoint, venvPath, guestCtx, lastModified);
        }
    }

    /// Load a JavaScript extension based on its definition in the configuration.
    ///
    /// @param config extension definition from the Wolpi configuration
    /// @param lastModified optional last modified timestamp of the extension source, used to
    ///                     determine if a reload is necessary when live-reloading is enabled.
    ///                     Should only be set if the load is performed as part of a live-reload
    ///                     operation, otherwise it should be null.
    /// @return the loaded extension
    /// @throws ExtensionLoadException if loading the extension fails
    private LoadedExtension loadJsExtension(ExtensionConfig config, @Nullable Instant lastModified)
            throws ExtensionLoadException {

        String packageName;
        Path entryPoint;
        try {
            if (config.path() != null) {
                if (Files.isDirectory(config.path())
                        && Files.isRegularFile(config.path().resolve("package.json"))) {
                    packageName = jsInstaller.installExtensionFromLocalDirectory(config.path());
                    entryPoint = jsInstaller.getWolpiEntryPoint(packageName);
                } else if (Files.isRegularFile(config.path())) {
                    packageName = config.path().getFileName().toString().split("\\.")[0];
                    entryPoint = config.path();
                } else {
                    throw new ExtensionLoadException(
                            "Invalid JavaScript extension path, must point to a .js file or a directory with package.json");
                }
            } else if (config.npm() != null) {
                jsInstaller.installExtension(
                        config.npm().pkg(),
                        config.npm().version(),
                        config.npm().index(),
                        config.npm().indexAuth());
                entryPoint = jsInstaller.getWolpiEntryPoint(config.npm().pkg());
                packageName = config.npm().pkg();
            } else {
                throw new IllegalArgumentException("Invalid JavaScript extension configuration.");
            }
        } catch (PackageInstallException e) {
            throw new ExtensionLoadException("Failed to install JavaScript extension package", e);
        }

        if (entryPoint == null) {
            throw new ExtensionLoadException("Could not find entry point for extension.");
        }

        Source source;
        try {
            String modulePath = entryPoint.toAbsolutePath().toString();
            if (lastModified != null) {
                // When reloading, we have to bypass the module cache to pick up changes.
                modulePath += "?t=" + lastModified.toEpochMilli();
            }
            // This is a bit convoluted and could be so much easier if top-level await + dynamic
            // imports were working, but alas, they are not [1]. So instead, we do a wildcard import
            // and then export either the module's default export directly or the full set of named
            // exports as the extension object.
            //
            // [1]: See this issue: https://github.com/oracle/graaljs/issues/938
            source = Source.newBuilder("js", """
                            import * as allExports from '%s';
                            const { default: defaultExport, ...namedExports } = allExports;
                            export const hooks = defaultExport ?? namedExports;
                            """.formatted(modulePath), "wolpi-extension.js")
                    .mimeType("application/javascript+module")
                    .build();
        } catch (IOException e) {
            throw new ExtensionLoadException("Failed to load JavaScript extension source from " + entryPoint, e);
        }

        String extensionVersion = null;
        if (config.npm() != null) {
            extensionVersion = config.npm().version();
        } else if (Files.isDirectory(config.path())) {
            try {
                extensionVersion = jsInstaller.getVersion(packageName);
            } catch (PackageInstallException e) {
                log.warn("Failed to determine version of installed package: " + packageName, e);
            }
        }
        if (extensionVersion == null) {
            extensionVersion = "unknown";
        }
        if (packageName == null) {
            packageName = "unknown-js-extension";
        }

        String baseUri = null;
        if (wolpiConfig != null && wolpiConfig.http() != null) {
            var configuredBaseUri = wolpiConfig.http().baseUri();
            if (configuredBaseUri != null && !configuredBaseUri.isBlank()) {
                baseUri = configuredBaseUri;
            }
        }
        var guestCtx = guestContextFactory.createGuestContext(
                packageName, extensionVersion, config.config(), Language.JAVASCRIPT, baseUri);
        try (RuntimeContext ctx = new JSRuntimeContext(source, guestCtx, contextSupplier)) {
            var hooks = getExtensionHooks(ctx);
            var info = ctx.runHook(ExtensionHooks.INFO).as(ExtensionInfo.class);
            return new JSLoadedExtension(config, source, info, extensionVersion, hooks, guestCtx, lastModified);
        }
    }

    /// Get the list of loaded extensions that implement all the given hooks.
    ///
    /// @param hooks the hook(s) to check for
    /// @return the list of loaded extensions that implement all the given hooks, all extensions if no
    ///         hooks are given
    public List<LoadedExtension> getExtensions(ExtensionHooks... hooks) {
        if (hooks.length == 0) {
            // If no hooks are given, return all extensions
            return implementedHooks.values().stream()
                    .flatMap(List::stream)
                    .distinct()
                    .filter(e -> !disabledExtensions.contains(e))
                    // Make sure the order matches the one in the configuration
                    .sorted(Comparator.comparingInt(
                            e -> wolpiConfig.extensions().indexOf(e.config())))
                    .toList();
        }

        Set<LoadedExtension> matchingExtensions =
                new LinkedHashSet<>(implementedHooks.getOrDefault(hooks[0], List.of()));
        for (int i = 1; i < hooks.length; i++) {
            matchingExtensions.retainAll(implementedHooks.get(hooks[i]));
            if (matchingExtensions.isEmpty()) {
                break;
            }
        }
        matchingExtensions.removeAll(disabledExtensions);
        return matchingExtensions.stream()
                // Make sure the order matches the one in the configuration
                .sorted(Comparator.comparingInt(e -> wolpiConfig.extensions().indexOf(e.config())))
                .toList();
    }

    /// Temporarily disable all extensions except the given one.
    ///
    /// Used for running validation tests against a single extension in isolation.
    ///
    /// @param ext the extension to keep enabled
    /// @return an AutoCloseable that will re-enable all extensions when closed
    public TemporarilyIsolatedRegistry temporarilyIsolateExtension(LoadedExtension ext) {
        this.getExtensions().stream().filter(e -> e != ext).forEach(this.disabledExtensions::add);
        return new TemporarilyIsolatedRegistry(this);
    }

    /// Callback for when a file change is detected in an extension's source file/directory.
    private void onReload(String extName, ExtensionConfig config, AlterationEvent evt) {
        log.info("Change detected in extension {}, reloading...", extName);
        // First, clear the runtime context pool for the extension to ensure no stale contexts are
        // kept around
        var currentlyLoadedExt = loadedExtensions.get(config);
        try {
            extensionContextPool.clear(currentlyLoadedExt, false);
        } catch (Exception e) {
            log.warn(
                    "Failed to clear runtime context pool for extension {}, stale contexts may still be alive!",
                    extName,
                    e);
        }
        try {
            var newExt = loadExtension(config, evt.timestamp());
            log.info("Extension '{}' reloaded.", newExt.extensionInfo().name());
            // Notify any listeners about the reload
            reloadCallbacks.getOrDefault(config, List.of()).forEach(cb -> cb.accept(newExt));
        } catch (ExtensionLoadException e) {
            log.error("Failed to reload extension from {}", config, e);
        }
    }

    /// Register a callback to be called when the given extension is reloaded.
    ///
    /// @param config the extension configuration to register the callback for
    /// @param callback the callback to be called when the extension is reloaded, will be called
    ///                 in the thread that detected the file change
    public void addReloadCallback(ExtensionConfig config, Consumer<LoadedExtension> callback) {
        this.reloadCallbacks
                .computeIfAbsent(config, (k) -> new CopyOnWriteArrayList<>())
                .add(callback);
    }

    @Override
    public void close() throws Exception {
        if (this.fileMonitor != null) {
            this.fileMonitor.stop();
        }
    }

    /// AutoCloseable that re-enables all extensions when closed, used for temporarily isolating an
    /// extension during validation tests.
    public record TemporarilyIsolatedRegistry(ExtensionRegistry registry) implements AutoCloseable {
        @Override
        public void close() {
            this.registry.disabledExtensions.clear();
        }
    }
}
