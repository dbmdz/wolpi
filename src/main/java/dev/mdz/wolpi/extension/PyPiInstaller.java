package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.config.ExtensionConfig.IndexAuth;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.extension.util.CommandRunner;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;

/// Install a Python package from PyPI or a local directory into a dedicated virtual environment
/// using the system's Python installation.
///
/// For Wolpi extensions, the package must define a `wolpi-ext` entry point that points to a
/// callable that returns a WolpiExtension instance or a dictionary with the respective fields as
/// items.
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

    public static final String EXPECTED_GRAALPY_VERSION = "25";

    private final Path baseDir;
    private final @Nullable Path pythonPath;
    private final Duration processTimeout;
    private final JsonMapper jsonMapper;
    private final TomlMapper tomlMapper;
    private final boolean debuggingRequested;

    public PyPiInstaller(WolpiConfig config, JsonMapper jsonMapper) throws IOException {
        this.processTimeout = config.packaging().installTimeout();
        this.jsonMapper = jsonMapper;
        this.tomlMapper = new TomlMapper();
        this.baseDir = config.dataDirectory().resolve("pypi").toAbsolutePath().normalize();
        this.debuggingRequested =
                config.extensionDebug() != null && config.extensionDebug().enabled();
        Files.createDirectories(this.baseDir);

        Path pythonPath = config.packaging().pythonExecutable();
        if (pythonPath == null) {
            pythonPath = Stream.of("graalpy", "python3", "python")
                    .map(CommandRunner::findOnSystemPath)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
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

    ///  Install a Python package from the PyPI registry using an exact version.
    ///
    /// @param packageName  name of the package
    /// @param version      exact version (no ranges)
    /// @param customIndex  optional custom PyPI index URI
    /// @param skipDependencies  if true, do not install dependencies of the package
    public Path install(
            String packageName,
            String version,
            @Nullable URI customIndex,
            @Nullable IndexAuth indexAuth,
            boolean skipDependencies)
            throws PackageInstallException {
        Path venvPath = ensureVenv(packageName);

        String installedVersion = getVersion(packageName);
        if (version.equals(installedVersion)) {
            log.info("Package '{}' version {} already installed, skipping installation", packageName, version);
            return venvPath;
        }

        if (customIndex != null && indexAuth != null && indexAuth.username() != null && indexAuth.password() != null) {
            String urlString = customIndex.toString();
            if (!urlString.contains("://")) {
                log.warn(
                        "Custom index URL '{}' does not contain a valid scheme, cannot embed credentials", customIndex);
            } else {
                String encodedUsername = URLEncoder.encode(indexAuth.username(), StandardCharsets.UTF_8);
                String encodedPassword = URLEncoder.encode(indexAuth.password(), StandardCharsets.UTF_8);
                customIndex =
                        URI.create(urlString.replace("://", "://%s:%s@".formatted(encodedUsername, encodedPassword)));
            }
        }

        log.debug("Installing package '{}' version {} into virtual environment at {}", packageName, version, venvPath);
        List<String> pipArgs = new ArrayList<>();
        pipArgs.add("install");
        if (customIndex != null) {
            pipArgs.add("--extra-index-url");
            pipArgs.add(customIndex.toString());
        }
        if (skipDependencies) {
            pipArgs.add("--no-deps");
        }
        pipArgs.add("%s==%s".formatted(packageName, version));
        runPip(venvPath, pipArgs.toArray(String[]::new));
        return venvPath;
    }

    ///  Install a Wolpi extension package from the PyPI registry using an exact version.
    ///
    /// @param packageName  name of the package
    /// @param version      exact version (no ranges)
    /// @param customIndex  optional custom PyPI index URI
    /// @param indexAuth    optional authentication credentials for the custom index
    public void installExtension(
            String packageName, String version, @Nullable URI customIndex, @Nullable IndexAuth indexAuth)
            throws PackageInstallException, ExtensionLoadException {
        log.info(
                "Installing Python extension '{}:{}' from {}",
                packageName,
                version,
                customIndex == null ? "PyPI" : customIndex);
        this.install(packageName, version, customIndex, indexAuth, false);
        verifyInstalledExtension(packageName);
    }

    /// Install a Python package from a local directory containing a `pyproject.toml` file.
    ///
    /// @param localPackageDir  path to the local package directory
    /// @param skipDependencies if true, do not install dependencies of the package
    public void installFromLocalDirectory(Path localPackageDir, boolean skipDependencies)
            throws PackageInstallException {
        log.info("Installing Python extension from {}", localPackageDir.toAbsolutePath());
        if (!Files.isDirectory(localPackageDir) || !Files.isRegularFile(localPackageDir.resolve("pyproject.toml"))) {
            throw new IllegalArgumentException(
                    "localPackageDir must exist and contain a pyproject.toml: " + localPackageDir);
        }
        ParsedPackage pkgInfo = parsePyprojectToml(localPackageDir);
        Path existingSitePackages = getVenvSitePackages(pkgInfo.name());
        if (existingSitePackages != null) {
            // If the package is installed editable, it means it's already tracking the local
            // directory, so we can skip installation
            boolean isInstalledEditable = Files.exists(existingSitePackages.resolve(
                    "__editable__.%s-%s.pth".formatted(pkgInfo.name().replace('-', '_'), pkgInfo.version())));
            if (isInstalledEditable) {
                log.debug("Package '{}' already installed, skipping installation", pkgInfo.name());
                return;
            }
        }
        Path venvPath = ensureVenv(pkgInfo.name());
        runPip(
                venvPath,
                "install",
                skipDependencies ? "--no-deps" : "",
                supportsEditableInstalls() ? "-e" : "",
                localPackageDir.toAbsolutePath().toString());
    }

    /// Install a Wolpi extension package from a local directory containing a `pyproject.toml` file.
    ///
    /// @return the name of the installed package as specified in `pyproject.toml`
    public String installExtensionFromLocalDirectory(Path localPackageDir, boolean liveReload)
            throws PackageInstallException, ExtensionLoadException {
        if (!Files.isDirectory(localPackageDir) || !Files.isRegularFile(localPackageDir.resolve("pyproject.toml"))) {
            throw new IllegalArgumentException(
                    "localPackageDir must exist and contain a pyproject.toml: " + localPackageDir);
        }
        if ((liveReload || debuggingRequested) && !supportsEditableInstalls()) {
            log.warn(
                    "Debugging or live-reloading requested, but the current Python executable does not support editable installations, please configure GraalPy {} for package installation. Debugging and live-reloading not supported for {}.",
                    EXPECTED_GRAALPY_VERSION,
                    localPackageDir);
        }
        String packageName = parsePackageNameFromPyproject(localPackageDir);
        installFromLocalDirectory(localPackageDir, false);
        verifyInstalledExtension(packageName);
        return packageName;
    }

    /// Obtain the `wolpi-ext` entry point for the given package.
    public @Nullable EntryPoint getWolpiEntryPoint(String packageName) throws ExtensionLoadException {
        Path installLocation;
        try {
            installLocation = getPackageMetadataLocation(packageName);
        } catch (PackageInstallException e) {
            throw new ExtensionLoadException(e);
        }

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
            throw new ExtensionLoadException("Invalid wolpi entry point specification '%s' in package %s"
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
        Path venvPath = getVenv(packageName);
        if (venvPath == null) {
            return null;
        }

        try {
            String out = runPip(venvPath, "show", packageName);
            return Arrays.stream(out.split("\n"))
                    .filter(line -> line.startsWith("Version:"))
                    .findFirst()
                    .map(line -> line.substring("Version:".length()).trim())
                    .orElse(null);
        } catch (PackageInstallException e) {
            return null;
        }
    }

    /// Check if the installer supports "editable" package installation, i.e. where the code
    /// is loaded from the source directory and not copied into the virtual environment.
    ///
    /// This allows live-reloading of code changes without re-installing the package and source-level
    /// debugging with proper file paths, but it only works with GraalPy and not with CPython for
    /// some reason, possibly due to how the editable installation is implemented in pip.
    public boolean supportsEditableInstalls() {
        if (pythonPath == null) {
            throw new IllegalStateException("Python executable not configured or not found.");
        }
        try {
            var version = CommandRunner.runCommand(pythonPath, null, processTimeout, "--version")
                    .trim()
                    .toLowerCase();
            if (!version.contains("graalpy")) {
                return false;
            }
            if (!version.contains("native %s".formatted(EXPECTED_GRAALPY_VERSION))) {
                log.warn(
                        "GraalPy version mismatch: expected version {}, but found version string: {}. Editable installations are not supported.",
                        EXPECTED_GRAALPY_VERSION,
                        version);
                return false;
            }
            return true;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private ParsedPackage parsePyprojectToml(Path localPackageDir) throws PackageInstallException {
        Path pyproject = localPackageDir.resolve("pyproject.toml");
        Map<String, Object> toml;
        try {
            toml = tomlMapper.readValue(pyproject.toFile(), new TypeReference<>() {});
        } catch (JacksonException e) {
            throw new PackageInstallException(e);
        }
        if (toml.containsKey("project")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> project = (Map<String, Object>) toml.get("project");
            var name = (String) project.get("name");
            var version = (String) project.get("version");
            if (name != null && version != null) {
                return new ParsedPackage(name, version);
            }
        }
        throw new PackageInstallException("Could not parse name and version from pyproject.toml: " + pyproject);
    }

    /// Parse the pyproject.toml in the package directory to extract the package name.
    private String parsePackageNameFromPyproject(Path localPackageDir) throws PackageInstallException {
        return parsePyprojectToml(localPackageDir).name();
    }

    public @Nullable Path getVenv(String packageName) {
        var p = baseDir.resolve(packageName);
        if (Files.isDirectory(p)) {
            return p;
        }
        return null;
    }

    /// Create a new virtual environment for the given package if it does not already exist.
    ///
    /// @return the path to the created or existing virtual environment
    Path ensureVenv(String packageName) throws PackageInstallException {
        if (pythonPath == null) {
            throw new PackageInstallException(
                    "Python executable not configured or not found. Cannot install package '%s'."
                            .formatted(packageName));
        }
        Path venvPath = baseDir.resolve(packageName);
        if (!Files.exists(venvPath)) {
            log.debug("Creating virtual environment for package '{}' at {}", packageName, venvPath);
            try {
                CommandRunner.runCommand(
                        pythonPath,
                        baseDir,
                        processTimeout,
                        "-m",
                        "venv",
                        venvPath.toAbsolutePath().toString());
            } catch (IOException | InterruptedException e) {
                throw new PackageInstallException(
                        "Failed to create virtual environment for extension '%s'".formatted(packageName), e);
            }
        }
        return venvPath;
    }

    /// Run pip with the given arguments in the given virtual environment.
    private String runPip(Path venvPath, String... args) throws PackageInstallException {
        Path pythonInVenv = venvPath.resolve("bin").resolve("python");
        List<String> cmd = new ArrayList<>();
        cmd.add("-m");
        cmd.add("pip");
        cmd.addAll(Arrays.stream(args).filter(s -> !s.isBlank()).toList());
        try {
            return CommandRunner.runCommand(pythonInVenv, baseDir, processTimeout, cmd.toArray(new String[0]));
        } catch (IOException | InterruptedException e) {
            throw new PackageInstallException(e);
        }
    }

    /// Get the location of the `.dist-info` directory for the installed package.
    private Path getPackageMetadataLocation(String packageName) throws PackageInstallException {
        Path venvPath = ensureVenv(packageName);
        String out = runPip(venvPath, "inspect");
        JsonNode root;
        try {
            root = jsonMapper.readTree(out);
        } catch (JacksonException e) {
            throw new PackageInstallException("Failed to parse pip inspect output: " + out, e);
        }
        var installed = root.get("installed");
        return StreamSupport.stream(installed.spliterator(), false)
                .filter(pkg -> pkg.get("metadata").get("name").asString().equals(packageName))
                .map(pkg -> Path.of(pkg.get("metadata_location").asString()))
                .findFirst()
                .orElseThrow(
                        () -> new PackageInstallException("Could not determine install location for " + packageName));
    }

    private void verifyInstalledExtension(String packageName) throws ExtensionLoadException {
        try {
            getPackageMetadataLocation(packageName);
            getWolpiEntryPoint(packageName);
        } catch (PackageInstallException e) {
            throw new ExtensionLoadException(e);
        }
    }

    /// Definition of the `wolpi-ext` entry point from package metadata
    public record EntryPoint(String module, String function) {}

    private record ParsedPackage(String name, String version) {}
}
