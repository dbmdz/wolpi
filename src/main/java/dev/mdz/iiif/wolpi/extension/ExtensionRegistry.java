package dev.mdz.iiif.wolpi.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mdz.iiif.wolpi.config.ExtensionConfig;
import dev.mdz.iiif.wolpi.config.WolpiConfig;
import dev.mdz.iiif.wolpi.extension.PyPiInstaller.EntryPoint;
import dev.mdz.iiif.wolpi.model.extensions.ExtensionContext;
import dev.mdz.iiif.wolpi.model.extensions.ExtensionHooks;
import dev.mdz.iiif.wolpi.model.extensions.ExtensionInfo;
import dev.mdz.iiif.wolpi.model.extensions.LoadedExtension;
import dev.mdz.iiif.wolpi.model.extensions.LoadedExtension.Language;
import dev.mdz.iiif.wolpi.model.extensions.LoadedExtension.RuntimeContext;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.TypeLiteral;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.jspecify.annotations.Nullable;
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
///   entry point that exports the hooks.
///
/// For Python extensions, these requirements apply:
/// - Must be a single .py file, a directory containing a Python package or a package from PyPI.
/// - Single Files must eiher:
///   - Define the hooks as top-level functions in the file.
///   - Define a `wolpi_extension()` function that returns an object with the hooks as methods.
/// - Packages must define a `wolpi-ext` entry point in their `pyproject.toml` or `setup.py` that
///   points to a callable that returns an object with the hooks as methods.
@Component
public class ExtensionRegistry {

  private static final TypeLiteral<Map<String, Object>> JSON_LIKE_OBJECT = new TypeLiteral<>() {};

  private final Engine jsEngine;
  private final Engine pythonEngine;
  private final Map<ExtensionHooks, List<LoadedExtension>> implementedHooks;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final PyPiInstaller pyInstaller;
  private final NpmInstaller jsInstaller;
  private final String wolpiVersion;

  public ExtensionRegistry(
      WolpiConfig cfg,
      ObjectMapper objectMapper,
      HttpClient httpClient,
      PyPiInstaller pyInstaller,
      NpmInstaller jsInstaller,
      BuildProperties buildProps)
      throws ExtensionLoadException {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.jsEngine = buildJsEngine();
    this.pythonEngine = buildPythonEngine();
    this.pyInstaller = pyInstaller;
    this.jsInstaller = jsInstaller;
    this.wolpiVersion = buildProps.getVersion();

    this.implementedHooks = new HashMap<>();
    for (ExtensionConfig extension : cfg.extensions()) {
      LoadedExtension loadedExtension = loadExtension(extension);
      for (ExtensionHooks hook : loadedExtension.implementedHooks()) {
        implementedHooks
            .computeIfAbsent(hook, (k) -> new ArrayList<>())
            .add(loadedExtension);
      }
    }
  }

  /// Build a GraalJS engine. This is shared between all extension contexts for duration of the
  /// application
  private static Engine buildJsEngine() {
    return Engine.newBuilder("js").build();
  }

  /// Build a GraalPy engine. This is shared between all extension contexts for duration of the
  /// application
  private static Engine buildPythonEngine() {
    return Engine.newBuilder("python").build();
  }

  ///  Construct a new JavaScript context for executing extension code.
  ///
  /// The lifetime of this context should be bound to a single request-response cycle.
  private static Context getJsContext(Engine engine) {
    return Context.newBuilder("js")
        .allowAllAccess(true)
        .engine(engine)
        .allowIO(IOAccess.newBuilder().fileSystem(new ESMFileSystem()).build())
        .option("js.esm-eval-returns-exports", "true")
        .build();
  }

  /// Construct a new Python context for executing extension code.
  ///
  /// The lifetime of this context should be bound to a single request-response cycle.
  ///
  /// @param engine the shared GraalVM Python engine
  private static Context getPythonContext(Engine engine) {
    return Context.newBuilder("python")
        .allowAllAccess(true)
        .option("python.IsolateNativeModules", "true")
        .engine(engine)
        .build();
  }

  ///  Load an extension based on its definition in the configuration
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

  /// Create a [LoadedExtension] instance for an extension.
  ///
  /// Called by the language-specific loading methods after they have initialized a GraalVM context
  /// for retrieving the extension information and hooks.
  ///
  /// @param lang the programming language of the extension
  /// @param hooks the polyglot value containing the extension hooks as members
  /// @param config the extension configuration
  /// @param extensionVersion the version of the extension
  /// @param contextSupplier a supplier that creates a new GraalVM context for the extension
  /// @param hooksSupplier a function that extracts the hooks from the context
  /// @return the loaded extension
  private LoadedExtension extensionFromPolyglotValue(
      Language lang,
      Value hooks,
      ExtensionConfig config,
      String extensionVersion,
      Supplier<Context> contextSupplier,
      Function<Context, Value> hooksSupplier) {
    Map<String, Object> infoJson = hooks.getMember("info").execute().as(JSON_LIKE_OBJECT);
    ExtensionInfo extensionInfo = objectMapper.convertValue(infoJson, ExtensionInfo.class);

    Set<ExtensionHooks> implementedHooks =
        hooks.getMemberKeys().stream()
            .map(
                k ->
                    switch (k) {
                      case "resolve" -> ExtensionHooks.RESOLVE;
                      case "authorize" -> ExtensionHooks.AUTHORIZE;
                      case "preProcessImage", "pre_process_image" ->
                          ExtensionHooks.PREPROCESS_IMAGE;
                      case "preScale", "pre_scale" -> ExtensionHooks.SCALE;
                      case "preCrop", "pre_crop" -> ExtensionHooks.CROP;
                      case "preRotate", "pre_rotate" -> ExtensionHooks.ROTATE;
                      case "preQuality", "pre_quality" -> ExtensionHooks.COLOR;
                      case "preFormat", "pre_format" -> ExtensionHooks.FORMAT;
                      default -> null;
                    })
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

    return new LoadedExtension(
        lang,
        extensionInfo,
        () -> {
          var wolpiCtx =
              new ExtensionContext(wolpiVersion, extensionVersion, httpClient, config.config());
          var graalCtx = contextSupplier.get();
          var bindings = hooksSupplier.apply(graalCtx);
          graalCtx.getBindings(lang.graalName()).putMember("wolpi", wolpiCtx);
          return new RuntimeContext(graalCtx, wolpiCtx, bindings);
        },
        implementedHooks);
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
    if (isLocalPackage) {
      packageName = pyInstaller.installFromLocalDirectory(config.path());
      sitePackagesPath = pyInstaller.getVenvSitePackages(packageName);
      if (sitePackagesPath == null) {
        throw new ExtensionLoadException(
            "Could not find virtual environment for installed package: " + packageName);
      }
      entryPoint = pyInstaller.getEntryPoint(packageName);
      extensionVersion = pyInstaller.getVersion(packageName);
    } else if (isPyPi) {
      packageName = config.pypi().pkg();
      pyInstaller.install(config.pypi().pkg(), config.pypi().version(), config.pypi().index());
      sitePackagesPath = pyInstaller.getVenvSitePackages(config.pypi().pkg());
      if (sitePackagesPath == null) {
        throw new ExtensionLoadException(
            "Could not find virtual environment for installed package: " + config.pypi().pkg());
      }
      entryPoint = pyInstaller.getEntryPoint(config.pypi().pkg());
      extensionVersion = config.pypi().version();
    } else if (isLocalFile) {
      entryPoint = null;
      sitePackagesPath = null;
    } else {
      throw new IllegalArgumentException("Invalid Python extension configuration.");
    }

    try {
      if (entryPoint != null) {
        // For packages, we have to import the function returning the implementation
        // from the entrypoint module.
        Path venvPath = sitePackagesPath.getParent().getParent().getParent();
        source =
            Source.newBuilder(
                    "python",
                    """
                    import sys
                    sys.prefix = "%s"
                    sys.path.append("%s")
                    from %s import %s
                    """
                        .formatted(venvPath, sitePackagesPath, entryPoint.module(), entryPoint.function()),
                    "%s.py".formatted(packageName))
                .build();
      } else {
        // Simple python files can be loaded directly.
        assert config.path() != null;
        source = Source.newBuilder("python", config.path().toFile()).build();
      }
    } catch (IOException e) {
      throw new ExtensionLoadException("Failed to load Python extension source.", e);
    }

    // Context is needed for loading to enumerate the hooks and call the info hook
    try (Context ctx = getPythonContext(this.pythonEngine)) {
      ctx.eval(source);
      var hooks = getPythonHooks(ctx, source, entryPoint);
      return extensionFromPolyglotValue(
          Language.PYTHON,
          hooks,
          config,
          extensionVersion == null ? "unknown" : extensionVersion,
          () -> ExtensionRegistry.getPythonContext(pythonEngine),
          c -> {
            try {
              return getPythonHooks(c, source, entryPoint);
            } catch (ExtensionLoadException e) {
              // Should not happen, if they loaded fine during discovery, they should load
              // fine during runtime.
              throw new RuntimeException(e);
            }
          });
    }
  }

  ///  Given a Python [Source] and a GraalPy [Context], load the extension hooks from it.
  ///
  /// @param ctx the GraalPy context
  /// @param source the source containing the extension code or importing it
  /// @param entryPoint optional entry point definition, if the extension is a package
  /// @return a [Value] containing the extension hooks as members
  /// @throws ExtensionLoadException if loading the hooks fails
  private Value getPythonHooks(Context ctx, Source source, @Nullable EntryPoint entryPoint)
      throws ExtensionLoadException {
    ctx.eval(source);
    var bindings = ctx.getBindings("python");

    Value hooks;
    if (entryPoint != null) {
      if (!bindings.hasMember(entryPoint.function())) {
        throw new IllegalArgumentException(
            "Entry point function '%s' not found in extension.".formatted(entryPoint.function()));
      }
      var rv = bindings.getMember(entryPoint.function()).execute();
      if (rv.hasHashEntries()) {
        // If the entry point returned a dict, we copy its executable members as members of
        // the top-level scope and use that as the hooks object.
        var it = rv.getHashKeysIterator();
        while (it.hasIteratorNextElement()) {
          var key = it.getIteratorNextElement().asString();
          Value v = rv.getHashValue(key);
          if (!v.canExecute()) {
            continue;
          }
          bindings.putMember(key, v);
        }
        hooks = bindings;
      } else {
        // Otherwise we assume that the entry point returned an object with the hooks as members.
        hooks = rv;
      }
    } else {
      hooks = bindings;
    }

    var functions =
          hooks.getMemberKeys().stream()
              .filter(key -> !key.startsWith("_") && hooks.getMember(key).canExecute())
              .toList();

    if (functions.isEmpty()) {
      throw new ExtensionLoadException("Extension did not define any top-level functions.");
    }
    if (!hooks.hasMember("info") || !hooks.getMember("info").canExecute()) {
      throw new ExtensionLoadException("Extension does not provide the 'info' hook, cannot load.");
    }
    return hooks;
  }

  /// Load a JavaScript extension based on its definition in the configuration.
  ///
  /// @param config extension definition from the Wolpi configuration
  /// @return the loaded extension
  /// @throws ExtensionLoadException if loading the extension fails
  private LoadedExtension loadJsExtension(ExtensionConfig config) throws ExtensionLoadException {

    String packageName = null;
    Path entryPoint;
    if (config.path() != null) {
      if (Files.isDirectory(config.path())
          && Files.isRegularFile(config.path().resolve("package.json"))) {
        packageName = jsInstaller.installFromLocalDirectory(config.path());
        entryPoint = jsInstaller.getEntryPoint(packageName);
      } else if (Files.isRegularFile(config.path())) {
        entryPoint = config.path();
      } else {
        throw new ExtensionLoadException(
            "Invalid JavaScript extension path, must point to a .js file or a directory with package.json");
      }
    } else if (config.npm() != null) {
      jsInstaller.install(config.npm().pkg(), config.npm().version(), config.npm().index());
      entryPoint = jsInstaller.getEntryPoint(config.npm().pkg());
      packageName = config.npm().pkg();
    } else {
      throw new IllegalArgumentException("Invalid JavaScript extension configuration.");
    }

    if (entryPoint == null) {
      throw new ExtensionLoadException("Could not find entry point for extension.");
    }

    try (Context ctx = getJsContext(this.jsEngine)) {
      Source source =
          Source.newBuilder("js", entryPoint.toFile())
              .mimeType("application/javascript+module")
              .build();
      Value exports = ctx.eval(source);
      if (exports == null || exports.isNull()) {
        throw new ExtensionLoadException("Extension did not export anything.");
      }

      // We support both named exports (where the hooks are individually exported) and default
      // exports (where a single object containing the hooks is exported as the default).
      // The default export is only used if it is the only export, otherwise we assume that the
      // other exports are the hooks.
      if (exports.getMemberKeys().size() == 1 && exports.hasMember("default")) {
        exports = exports.getMember("default");
      }
      if (!exports.hasMember("info")) {
        throw new ExtensionLoadException("Extension does not have an 'info' key in its exports.");
      }

      String extensionVersion = null;
      if (config.npm() != null) {
        extensionVersion = config.npm().version();
      } else if (packageName != null) {
        extensionVersion = jsInstaller.getVersion(packageName);
      }

      return extensionFromPolyglotValue(
          Language.JAVASCRIPT,
          exports,
          config,
          extensionVersion == null ? "unknown" : extensionVersion,
          () -> ExtensionRegistry.getJsContext(jsEngine),
          c -> {
            var bindings = c.eval(source);
            if (bindings.getMemberKeys().size() == 1 && bindings.hasMember("default")) {
              return bindings.getMember("default");
            } else {
              return bindings;
            }
          });
    } catch (IOException e) {
      throw new ExtensionLoadException(e);
    }
  }

  ///  Check if there is at least one loaded extension that implements the given hook.
  boolean hasHook(ExtensionHooks hook) {
    return implementedHooks.containsKey(hook);
  }

  /// Get the list of loaded extensions that implement the given hook.
  List<LoadedExtension> getExtensions(ExtensionHooks hook) {
    return implementedHooks.getOrDefault(hook, List.of());
  }
}
