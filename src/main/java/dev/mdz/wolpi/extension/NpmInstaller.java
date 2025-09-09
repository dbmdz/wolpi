package dev.mdz.wolpi.extension;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.util.CommandRunner;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// NpmInstaller installs npm packages into a dedicated base directory using the system's npm
/// binary.
///
/// All extensions are installed into a single `node_modules` directory under the base directory.
/// This is not a problem in node.js, as every package has its own `node_modules` subtree for its
/// dependencies, so no conflicts should arise between different extensions.
///
/// Implemented by shelling out to the system's `npm` binary for simplicity, there's unfortunately
/// no easy-to-use off-the-shelf Java or JavaScript library for installing npm packages
/// programmatically.
@Component
public class NpmInstaller {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Path baseDir;
  private final @Nullable Path npmPath;
  private final Duration processTimeout;
  private final ObjectMapper objectMapper;

  public NpmInstaller(WolpiConfig config, ObjectMapper objectMapper) throws ExtensionLoadException {
    this.processTimeout = config.packaging().installTimeout();
    this.objectMapper = objectMapper;
    this.baseDir = config.dataDirectory().resolve("npm").toAbsolutePath().normalize();
    try {
      Files.createDirectories(this.baseDir);
    } catch (IOException e) {
      throw new ExtensionLoadException("Failed to create base directory: " + this.baseDir, e);
    }

    Path npmPath = config.packaging().npmExecutable();
    if (npmPath == null) {
      npmPath = CommandRunner.findOnSystemPath("npm");
    }
    if (npmPath == null || !Files.isRegularFile(npmPath) || !Files.isExecutable(npmPath)) {
      log.warn(
          "npm executable not found or not executable: '%s'. Installing extensions from npm or local packages disabled."
              .formatted(npmPath));
      this.npmPath = null;
    } else {
      this.npmPath = npmPath;
    }
  }

  /// Install a package from the npm registry using an exact version.
  ///
  /// @param packageName    name of the package, may include scope (e.g. "@scope/pkg")
  /// @param version        exact version (no ranges)
  /// @param customRegistry optional custom npm registry URI
  /// @throws ExtensionLoadException if the installation fails
  public void install(String packageName, String version, @Nullable URI customRegistry)
      throws ExtensionLoadException {
    if (npmPath == null) {
      throw new ExtensionLoadException("npm executable not configured, cannot install package");
    }
    List<String> args =
        List.of(
            "install",
            "--no-audit",
            "--no-fund",
            "--no-package-lock",
            "--no-save",
            "--prefix",
            baseDir.toString(),
            baseDir.toString(),
            "%s@%s".formatted(packageName, version));
    if (customRegistry != null) {
      args = new ArrayList<>(args);
      args.add(1, "--registry");
      args.add(2, customRegistry.toString());
    }
    try {
      CommandRunner.runCommand(npmPath, baseDir, processTimeout, args.toArray(String[]::new));
    } catch (IOException | InterruptedException e) {
      throw new ExtensionLoadException(e);
    }
    verifyInstalledPackage(packageName);
  }

  /// Install a package from a local directory that contains a package.json.
  ///
  /// @param localPackageDir path to a directory with package.json
  /// @return the installed package name as read from package.json
  /// @throws ExtensionLoadException if installation fails
  public String installFromLocalDirectory(Path localPackageDir) throws ExtensionLoadException {
    localPackageDir = localPackageDir.toAbsolutePath().normalize();
    if (npmPath == null) {
      throw new ExtensionLoadException(
          "npm executable not found or configured, cannot install package in " + localPackageDir);
    }
    if (!Files.isDirectory(localPackageDir)
        || !Files.isRegularFile(localPackageDir.resolve("package.json"))) {
      throw new ExtensionLoadException(
          "localPackageDir must exist and contain a package.json: " + localPackageDir);
    }

    try {
      CommandRunner.runCommand(
          npmPath,
          baseDir,
          processTimeout,
          "install",
          "--no-audit",
          "--no-fund",
          "--no-package-lock",
          "--no-save",
          "--prefix",
          baseDir.toString(),
          localPackageDir.toAbsolutePath().toString());
    } catch (IOException | InterruptedException e) {
      throw new ExtensionLoadException(e);
    }

    // Verify using the package name obtained from local package.json
    PackageJsonInfo info;
    info = parsePackageJson(localPackageDir);
    verifyInstalledPackage(info.name());
    return info.name();
  }

  /// Returns the root directory for an installed package under node_modules. For scoped packages,
  /// this returns `{baseDir}/node_modules/@{scope}/{name}`.
  public @Nullable Path getPackageRoot(String packageName) {
    Path nm = baseDir.resolve("node_modules");
    Path root;
    if (packageName.startsWith("@")) {
      String[] parts = packageName.split("/", 2);
      if (parts.length != 2 || parts[1].isBlank()) {
        throw new IllegalArgumentException("Invalid scoped package name: " + packageName);
      }
      root = nm.resolve(parts[0]).resolve(parts[1]);
    }
    root = nm.resolve(packageName);
    if (!Files.isDirectory(root)) {
      return null;
    }
    return root;
  }

  /// Parse package.json using Jackson to extract name, version, and ESM entry point.
  private PackageJsonInfo parsePackageJson(Path packageRoot) throws ExtensionLoadException {
    Path pkgJson = packageRoot.resolve("package.json");
    if (!Files.isRegularFile(pkgJson)) {
      throw new ExtensionLoadException("No package.json found in package root: " + packageRoot);
    }
    Map<String, Object> root;
    try {
      root = objectMapper.readValue(Files.readAllBytes(pkgJson), new TypeReference<>() {});
    } catch (IOException e) {
      throw new ExtensionLoadException(
          "Failed to parse package.json at '%s'".formatted(packageRoot), e);
    }
    String name;
    if (root.get("name") instanceof String nameStr) {
      name = nameStr;
    } else {
      throw new ExtensionLoadException("Package has no name field: " + pkgJson);
    }
    String version;
    if (root.get("version") instanceof String versionStr) {
      version = versionStr;
    } else {
      throw new ExtensionLoadException("Package has no version field: " + pkgJson);
    }

    String esmEntry;
    Object exports = root.get("exports");
    if (exports instanceof String exportString) {
      esmEntry = exportString;
    } else {
      throw new ExtensionLoadException(
          "Package has no exports field with a string value: " + pkgJson);
    }
    return new PackageJsonInfo(name, version, packageRoot.resolve(esmEntry));
  }

  /// Get the path to the entry point for the package
  public @Nullable Path getEntryPoint(String packageName) throws ExtensionLoadException {
    Path pkg = getPackageRoot(packageName);
    if (pkg == null) {
      throw new ExtensionLoadException("Package not installed: " + packageName);
    }
    PackageJsonInfo info = parsePackageJson(pkg);
    return info.esmEntryPoint;
  }

  ///  Get the version of the installed package
  public String getVersion(String packageName) throws ExtensionLoadException {
    Path pkg = getPackageRoot(packageName);
    if (pkg == null) {
      throw new ExtensionLoadException("Package not installed: " + packageName);
    }
    PackageJsonInfo info = parsePackageJson(pkg);
    return info.version();
  }

  public record PackageJsonInfo(String name, String version, Path esmEntryPoint) {}

  private void verifyInstalledPackage(String packageName) throws ExtensionLoadException {
    if (getEntryPoint(packageName) == null) {
      throw new ExtensionLoadException(
          "Failed to determine entry point for package: " + packageName);
    }
  }
}
