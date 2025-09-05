package dev.mdz.iiif.wolpi.extension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import dev.mdz.iiif.wolpi.config.WolpiConfig;
import dev.mdz.iiif.wolpi.config.WolpiConfig.PackagingConfig;
import dev.mdz.iiif.wolpi.exceptions.ExtensionLoadException;
import dev.mdz.iiif.wolpi.util.CommandRunner;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/// Install a Python package from PyPI or a local directory into a dedicated virtual environment
/// using the system's Python installation.
///
/// The package must define a `wolpi-ext` entry point that points to a callable that returns a
/// WolpiExtension instance.
///
/// If installing from a local directory, the directory must contain a `pyproject.toml` file with
/// the package metadata, including the entry point.
///
/// If using packages with native dependencies, the [PackagingConfig#pythonExecutable] configuration
/// property must be set to a `graalpy` executable[1] so that GraalPy can apply necessary patches to
/// the native libraries and/or override the versions to select a compatible one.
///
/// [1] [Download from `oracle/graalpython`](https://github.com/oracle/graalpython/releases)
@Component
public class PyPiInstaller {
  private static final Logger log =
      org.slf4j.LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Path baseDir;
  private final @Nullable Path pythonPath;
  private final Duration processTimeout;
  private final ObjectMapper jsonMapper;
  private final TomlMapper tomlMapper;

  public PyPiInstaller(WolpiConfig config, ObjectMapper jsonMapper) throws IOException {
    this.processTimeout = config.packaging().installTimeout();
    this.jsonMapper = jsonMapper;
    this.tomlMapper = new TomlMapper();
    this.baseDir = config.dataDirectory().resolve("pypi").toAbsolutePath().normalize();
    Files.createDirectories(this.baseDir);

    Path pythonPath = config.packaging().pythonExecutable();
    if (pythonPath == null) {
      pythonPath =
          Optional.ofNullable(CommandRunner.findOnSystemPath("python3"))
              .orElse(CommandRunner.findOnSystemPath("python"));
    }
    if (pythonPath == null || !Files.isRegularFile(pythonPath) || !Files.isExecutable(pythonPath)) {
      log.warn(
          "Python executable not found or not executable: '%s'. Extensions from PyPi or local packages will be disabled."
              .formatted(pythonPath));
      this.pythonPath = null;
    } else {
      this.pythonPath = pythonPath;
    }
  }

  ///  Install a package from the PyPI registry using an exact version.
  ///
  /// @param packageName  name of the package
  /// @param version      exact version (no ranges)
  /// @param customIndex  optional custom PyPI index URI
  public void install(String packageName, String version, @Nullable URI customIndex)
      throws ExtensionLoadException {
    Path venvPath = ensureVenv(packageName);
    runPip(venvPath, "install", "%s==%s".formatted(packageName, version));
    verifyInstalledPackage(packageName);
  }

  /// Install a package from a local directory containing a `pyproject.toml` file.
  ///
  /// @return the name of the installed package as specified in `pyproject.toml`
  public String installFromLocalDirectory(Path localPackageDir) throws ExtensionLoadException {
    if (!Files.isDirectory(localPackageDir)
        || !Files.isRegularFile(localPackageDir.resolve("pyproject.toml"))) {
      throw new IllegalArgumentException(
          "localPackageDir must exist and contain a pyproject.toml: " + localPackageDir);
    }
    String packageName = parsePackageNameFromPyproject(localPackageDir);
    Path venvPath = ensureVenv(packageName);
    runPip(venvPath, "install", localPackageDir.toAbsolutePath().toString());
    verifyInstalledPackage(packageName);
    return packageName;
  }

  /// Obtain the `wolpi-ext` entry point for the given package.
  public @Nullable EntryPoint getEntryPoint(String packageName) throws ExtensionLoadException {
    Path installLocation = getPackageMetadataLocation(packageName);

    Path entryPointsFile = installLocation.resolve("entry_points.txt");
    if (!Files.isRegularFile(entryPointsFile)) {
      throw new ExtensionLoadException("Could not find entry_points.txt in " + installLocation);
    }

    String[] entryPointsLines;
    try {
      entryPointsLines = Files.readString(entryPointsFile).split("\n");
    } catch (IOException e) {
      throw new ExtensionLoadException("Failed to read " + entryPointsFile, e);
    }
    String entryPointSpec = null;
    boolean inWolpiSection = false;
    for (String line : entryPointsLines) {
      if (line.trim().equals("[wolpi]")) {
        inWolpiSection = true;
        continue;
      }
      if (line.startsWith("[") && line.endsWith("]")) {
        inWolpiSection = false;
      }
      if (!line.contains(" = ")) {
        continue;
      }
      if (inWolpiSection) {
        String[] parts = line.split("=");
        if (parts.length != 2) {
          continue;
        }
        entryPointSpec = parts[1].trim();
        break;
      }
    }
    if (entryPointSpec == null) {
      throw new ExtensionLoadException("No 'wolpi' entry point found for " + packageName);
    }
    String[] moduleAndFunction = entryPointSpec.split(":");
    if (moduleAndFunction.length != 2) {
      throw new ExtensionLoadException(
          "Invalid wolpi entry point specification '%s' in package %s"
              .formatted(entryPointSpec, packageName));
    }
    return new EntryPoint(moduleAndFunction[0].trim(), moduleAndFunction[1].trim());
  }

  ///  Get the path of the virtual environment for the given package, or null if it does not exist.
  public @Nullable Path getVenvSitePackages(String packageName) {
    var venv = baseDir.resolve(packageName);
    if (!Files.isDirectory(venv)) {
      return null;
    }
    try (var stream = Files.newDirectoryStream(venv.resolve("lib"))) {
      for (var path : stream) {
        if (Files.isDirectory(path) && path.getFileName().toString().startsWith("python")) {
          var sitePackages = path.resolve("site-packages");
          if (Files.isDirectory(sitePackages)) {
            return sitePackages;
          }
        }
      }
      return null;
    } catch (IOException e) {
      return null;
    }
  }

  // Determine the installed version of the given package, or null if not installed.
  public @Nullable String getVersion(String packageName) {
    try {
      Path venvPath = ensureVenv(packageName);
      String out = runPip(venvPath, "show", packageName);
      return Arrays.stream(out.split("\n"))
          .filter(line -> line.startsWith("Version:"))
          .findFirst()
          .map(line -> line.substring("Version:".length()).trim())
          .orElse(null);
    } catch (ExtensionLoadException e) {
      return null;
    }
  }

  /// Parse the pyproject.toml in the package directory to extract the package name.
  private String parsePackageNameFromPyproject(Path localPackageDir) throws ExtensionLoadException {
    Path pyproject = localPackageDir.resolve("pyproject.toml");
    Map<String, Object> toml;
    try {
      toml = tomlMapper.readValue(pyproject.toFile(), new TypeReference<>() {});
    } catch (IOException e) {
      throw new ExtensionLoadException(e);
    }
    if (toml.containsKey("project")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> project = (Map<String, Object>) toml.get("project");
      if (project.containsKey("name")) {
        return (String) project.get("name");
      }
    }
    throw new ExtensionLoadException("Could not find package name in pyproject.toml: " + pyproject);
  }

  /// Create a new virtual environment for the given package if it does not already exist.
  ///
  /// @return the path to the created or existing virtual environment
  Path ensureVenv(String packageName) throws ExtensionLoadException {
    if (pythonPath == null) {
      throw new ExtensionLoadException(
          "Python executable not configured or not found. Cannot install package '%s'."
              .formatted(packageName));
    }
    Path venvPath = baseDir.resolve(packageName);
    if (!Files.exists(venvPath)) {
      try {
        CommandRunner.runCommand(
            pythonPath,
            baseDir,
            processTimeout,
            "-m",
            "venv",
            venvPath.toAbsolutePath().toString());
      } catch (IOException | InterruptedException e) {
        throw new ExtensionLoadException(
            "Failed to create virtual environment for extension '%s'".formatted(packageName), e);
      }
    }
    return venvPath;
  }

  /// Run pip with the given arguments in the given virtual environment.
  private String runPip(Path venvPath, String... args) throws ExtensionLoadException {
    Path pythonInVenv = venvPath.resolve("bin").resolve("python");
    List<String> cmd = new ArrayList<>();
    cmd.add("-m");
    cmd.add("pip");
    cmd.addAll(Arrays.asList(args));
    try {
      return CommandRunner.runCommand(
          pythonInVenv, baseDir, processTimeout, cmd.toArray(new String[0]));
    } catch (IOException | InterruptedException e) {
      throw new ExtensionLoadException(e);
    }
  }

  /// Get the location of the `.dist-info` directory for the installed package.
  private Path getPackageMetadataLocation(String packageName) throws ExtensionLoadException {
    Path venvPath = ensureVenv(packageName);
    String out = runPip(venvPath, "inspect");
    JsonNode root;
    try {
      root = jsonMapper.readTree(out);
    } catch (JsonProcessingException e) {
      throw new ExtensionLoadException("Failed to parse pip inspect output: " + out, e);
    }
    var installed = root.get("installed");
    return StreamSupport.stream(installed.spliterator(), false)
        .filter(pkg -> pkg.get("metadata").get("name").asText().equals(packageName))
        .map(pkg -> Path.of(pkg.get("metadata_location").asText()))
        .findFirst()
        .orElseThrow(
            () ->
                new ExtensionLoadException(
                    "Could not determine install location for " + packageName));
  }

  private void verifyInstalledPackage(String packageName) throws ExtensionLoadException {
    getPackageMetadataLocation(packageName);
    getEntryPoint(packageName);
  }

  /// Definition of the `wolpi-ext` entry point from package metadata
  public record EntryPoint(String module, String function) {}
}
