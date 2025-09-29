package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.config.ExtensionConfig;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.PyPiInstaller.EntryPoint;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.extension.model.ExtensionGuestContext;
import dev.mdz.wolpi.extension.model.ExtensionHooks;
import dev.mdz.wolpi.extension.model.ExtensionInfo;
import dev.mdz.wolpi.extension.model.JSLoadedExtension;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.extension.model.PythonLoadedExtension;
import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.graalvm.polyglot.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
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
public class ExtensionRegistry {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Map<ExtensionHooks, List<LoadedExtension>> implementedHooks;
  private final HttpClient httpClient;
  private final PyPiInstaller pyInstaller;
  private final NpmInstaller jsInstaller;
  private final String wolpiVersion;

  public ExtensionRegistry(
      WolpiConfig cfg,
      HttpClient httpClient,
      PyPiInstaller pyInstaller,
      NpmInstaller jsInstaller,
      BuildProperties buildProps) {
    this.httpClient = httpClient;
    this.pyInstaller = pyInstaller;
    this.jsInstaller = jsInstaller;
    this.wolpiVersion = buildProps.getVersion();

    this.implementedHooks = new HashMap<>();
    if (cfg.extensions().isEmpty()) {
      return;
    }
    // Parallelize extension loading to speed up application startup time
    try (ExecutorService pool = Executors.newFixedThreadPool(cfg.extensions().size())) {
      var futs =
          cfg.extensions().stream()
              .map(
                  ext ->
                      pool.submit(
                          () -> {
                            long start = System.nanoTime();
                            var loaded = loadExtension(ext);
                            log.info(
                                "Extension '%s' loaded in %.2f ms"
                                    .formatted(
                                        loaded.extensionInfo().name(),
                                        (System.nanoTime() - start) / 1_000_000.0));
                            return loaded;
                          }))
              .toList();

      // Determine which extension implements which hooks
      for (var f : futs) {
        try {
          var ext = f.get();
          for (ExtensionHooks hook : ext.implementedHooks()) {
            implementedHooks.computeIfAbsent(hook, (k) -> new ArrayList<>()).add(ext);
          }
        } catch (InterruptedException | ExecutionException e) {
          log.error("Failed to load extension", e);
        }
      }
    }
  }

  /// Load an extension based on its definition in the configuration
  ///
  /// @param config extension definition from the Wolpi configuration
  /// @return the loaded extension
  /// @throws ExtensionLoadException if loading the extension fails
  private LoadedExtension loadExtension(ExtensionConfig config) throws ExtensionLoadException {
    if (config.path() != null) {
      Path path = config.path().toAbsolutePath().normalize();
      if (!Files.exists(path)) {
        throw new ExtensionLoadException("Extension path does not exist: " + path);
      }
      if (!Files.isReadable(path)) {
        throw new ExtensionLoadException("Extension path is not readable: " + path);
      }
      if (path.toString().endsWith(".js")) {
        return loadJsExtension(config);
      } else if (path.toString().endsWith(".py")) {
        return loadPythonExtension(config);
      }
      if (!Files.isDirectory(path)) {
        throw new ExtensionLoadException(
            "Extension path must be a directory or a .js/.py file: " + path);
      }
      if (Files.exists(path.resolve("pyproject.toml"))) {
        return loadPythonExtension(config);
      } else if (Files.exists(path.resolve("package.json"))) {
        return loadJsExtension(config);
      } else {
        throw new ExtensionLoadException(
            "Extension path must contain a package.json (for JS) or pyproject.toml (for Python): "
                + path);
      }
    } else if (config.npm() != null) {
      return loadJsExtension(config);
    } else if (config.pypi() != null) {
      return loadPythonExtension(config);
    } else {
      throw new IllegalArgumentException("Invalid extension configuration.");
    }
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
        ctx.run(
            ext ->
                PolyglotHelpers.dictOrMemberKeys(ext).stream()
                    .map(ExtensionHooks::fromName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()));
    if (!providedHooks.contains(ExtensionHooks.INFO)) {
      throw new ExtensionLoadException("Extension does not provide the 'info' hook, cannot load.");
    }
    if (!providedHooks.contains(ExtensionHooks.CLEANUP)) {
      throw new ExtensionLoadException(
          "Extension does not provide the 'cleanup' hook, cannot load.");
    }
    return providedHooks;
  }

  /// Load a Python extension based on its definition in the configuration.
  ///
  /// @param config extension definition from the Wolpi configuration
  /// @return the loaded extension
  /// @throws ExtensionLoadException if loading the extension fails
  private LoadedExtension loadPythonExtension(ExtensionConfig config)
      throws ExtensionLoadException {
    boolean isLocalFile = config.path() != null && Files.isRegularFile(config.path());
    boolean isLocalPackage = config.path() != null && Files.isDirectory(config.path());
    boolean isPyPi = config.pypi() != null;

    String packageName = null;
    String extensionVersion = null;
    final Source source;
    EntryPoint entryPoint;
    Path sitePackagesPath;

    try {
      if (isLocalPackage) {
        packageName = pyInstaller.installExtensionFromLocalDirectory(config.path());
        sitePackagesPath = pyInstaller.getVenvSitePackages(packageName);
        if (sitePackagesPath == null) {
          throw new ExtensionLoadException(
              "Could not find virtual environment for installed package: " + packageName);
        }
        entryPoint = pyInstaller.getWolpiEntryPoint(packageName);
        extensionVersion = pyInstaller.getVersion(packageName);
      } else if (isPyPi) {
        packageName = config.pypi().pkg();
        pyInstaller.installExtension(config.pypi().pkg(), config.pypi().version(),
            config.pypi().index());
        sitePackagesPath = pyInstaller.getVenvSitePackages(config.pypi().pkg());
        if (sitePackagesPath == null) {
          throw new ExtensionLoadException(
              "Could not find virtual environment for installed package: " + config.pypi().pkg());
        }
        entryPoint = pyInstaller.getWolpiEntryPoint(config.pypi().pkg());
        extensionVersion = config.pypi().version();
      } else if (isLocalFile) {
        entryPoint = null;
        sitePackagesPath = null;
      } else {
        throw new IllegalArgumentException("Invalid Python extension configuration.");
      }
    } catch (PackageInstallException e) {
      throw new ExtensionLoadException("Failed to install Python extension package", e);
    }

    Path venvPath;
    try {
      if (entryPoint != null) {
        // For packages, we have to import the function returning the implementation
        // from the entrypoint module.
        venvPath = sitePackagesPath.getParent().getParent().getParent();
        source =
            Source.newBuilder(
                    "python",
                    "from %s import %s\n".formatted(entryPoint.module(), entryPoint.function()),
                    "%s.py".formatted(packageName))
                .build();
      } else {
        // Simple python files can be loaded directly.
        venvPath = null;
        assert config.path() != null;
        source = Source.newBuilder("python", config.path().toFile()).build();
      }
    } catch (IOException e) {
      throw new ExtensionLoadException("Failed to load Python extension source", e);
    }

    if (extensionVersion == null) {
      extensionVersion = "unknown";
    }

    var guestCtx =
        new ExtensionGuestContext(wolpiVersion, extensionVersion, httpClient, config.config());

    try (RuntimeContext ctx = new PythonRuntimeContext(source, entryPoint, venvPath, null)) {
      return new PythonLoadedExtension(
          source,
          ctx.runHook(ExtensionHooks.INFO).as(ExtensionInfo.class),
          extensionVersion,
          getExtensionHooks(ctx),
          entryPoint,
          venvPath,
          guestCtx);
    }
  }

  /// Load a JavaScript extension based on its definition in the configuration.
  ///
  /// @param config extension definition from the Wolpi configuration
  /// @return the loaded extension
  /// @throws ExtensionLoadException if loading the extension fails
  private LoadedExtension loadJsExtension(ExtensionConfig config) throws ExtensionLoadException {

    String packageName = null;
    Path entryPoint;
    try {
      if (config.path() != null) {
        if (Files.isDirectory(config.path())
            && Files.isRegularFile(config.path().resolve("package.json"))) {
          packageName = jsInstaller.installExtensionFromLocalDirectory(config.path());
          entryPoint = jsInstaller.getWolpiEntryPoint(packageName);
        } else if (Files.isRegularFile(config.path())) {
          entryPoint = config.path();
        } else {
          throw new ExtensionLoadException(
              "Invalid JavaScript extension path, must point to a .js file or a directory with package.json");
        }
      } else if (config.npm() != null) {
        jsInstaller.installExtension(config.npm().pkg(), config.npm().version(),
            config.npm().index());
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
      source =
          Source.newBuilder("js", entryPoint.toFile())
              .mimeType("application/javascript+module")
              .build();
    } catch (IOException e) {
      throw new ExtensionLoadException(
          "Failed to load JavaScript extension source from " + entryPoint, e);
    }

    String extensionVersion = null;
    if (config.npm() != null) {
      extensionVersion = config.npm().version();
    } else if (packageName != null) {
      try {
        extensionVersion = jsInstaller.getVersion(packageName);
      } catch (PackageInstallException e) {
        log.warn("Failed to determine version of installed package: " + packageName, e);
      }
    }
    if (extensionVersion == null) {
      extensionVersion = "unknown";
    }

    var guestCtx =
        new ExtensionGuestContext(wolpiVersion, extensionVersion, httpClient, config.config());
    try (RuntimeContext ctx = new JSRuntimeContext(source, guestCtx)) {
      return new JSLoadedExtension(
          source,
          ctx.runHook(ExtensionHooks.INFO).as(ExtensionInfo.class),
          extensionVersion,
          getExtensionHooks(ctx),
          guestCtx);
    }
  }

  /// Get the list of loaded extensions that implement all the given hooks.
  ///
  /// @param hooks the hook(s) to check for
  /// @return the list of loaded extensions that implement all the given hooks, all extensions if no
  ///         hooks are given
  List<LoadedExtension> getExtensions(ExtensionHooks... hooks) {
    if (hooks.length == 0) {
      // If no hooks are given, return all extensions
      return implementedHooks.values().stream().flatMap(List::stream).distinct().toList();
    }

    Set<LoadedExtension> matchingExtensions =
        new LinkedHashSet<>(implementedHooks.getOrDefault(hooks[0], List.of()));
    for (int i = 1; i < hooks.length; i++) {
      matchingExtensions.retainAll(implementedHooks.get(hooks[i]));
      if (matchingExtensions.isEmpty()) {
        break;
      }
    }
    return new ArrayList<>(matchingExtensions);
  }
}
