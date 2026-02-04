package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.config.ExtensionConfig.IndexAuth;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.extension.util.CommandRunner;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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
    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final Path baseDir;
    private final @Nullable Path npmPath;
    private final Duration processTimeout;
    private final JsonMapper objectMapper;

    public NpmInstaller(WolpiConfig config, JsonMapper objectMapper) throws PackageInstallException {
        this.processTimeout = config.packaging().installTimeout();
        this.objectMapper = objectMapper;
        this.baseDir = config.dataDirectory().resolve("npm").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new PackageInstallException("Failed to create base directory: " + this.baseDir, e);
        }

        Path npmPath = config.packaging().npmExecutable();
        if (npmPath == null) {
            npmPath = CommandRunner.findOnSystemPath("npm");
        }
        if (npmPath == null || !Files.isExecutable(npmPath)) {
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
    /// @throws PackageInstallException if the installation fails
    public void installExtension(
            String packageName, String version, @Nullable URI customRegistry, @Nullable IndexAuth registryAuth)
            throws PackageInstallException, ExtensionLoadException {
        if (npmPath == null) {
            throw new PackageInstallException("npm executable not configured, cannot install package");
        }

        // Check if package is already installed with the correct version
        try {
            String installedVersion = getVersion(packageName);
            if (version.equals(installedVersion)) {
                log.info("Package '{}' version {} already installed, skipping installation", packageName, version);
                return;
            }
        } catch (PackageInstallException e) {
            // Package not installed, continue with installation
        }

        log.debug("Installing package '{}' version {} from npm registry", packageName, version);
        String npmScope;
        if (packageName.startsWith("@")) {
            npmScope = packageName.split("/")[0];
        } else {
            npmScope = null;
        }
        List<String> args = List.of(
                "install",
                "--no-audit",
                "--no-fund",
                "--no-package-lock",
                "--no-save",
                "--prefix",
                baseDir.toString(),
                baseDir.toString(),
                "%s@%s".formatted(packageName, version));
        Path customTempConfig = null;
        if (customRegistry != null) {
            try {
                customTempConfig = Files.createTempFile("wolpi-npmrc-", ".tmp");
                if (npmScope == null) {
                    Files.writeString(customTempConfig, "registry=%s\n".formatted(customRegistry));
                } else {
                    StringBuilder npmrcContent = new StringBuilder();
                    npmrcContent.append("%s:registry=%s\n".formatted(npmScope, customRegistry));
                    if (registryAuth != null) {
                        // Host with `//` prefixed, e.g. `//registry.example.com/`
                        String registryHost = customRegistry
                                .toString()
                                .substring(customRegistry.toString().indexOf("://") + 1);

                        if (registryAuth.token() != null) {
                            npmrcContent.append("%s:_authToken=%s\n".formatted(registryHost, registryAuth.token()));
                        } else if (registryAuth.username() != null && registryAuth.password() != null) {
                            String auth = Base64.getEncoder()
                                    .encodeToString("%s:%s"
                                            .formatted(registryAuth.username(), registryAuth.password())
                                            .getBytes());
                            npmrcContent.append("%s:_auth=%s\n".formatted(registryHost, auth));
                        }
                    }
                    Files.writeString(customTempConfig, npmrcContent.toString());
                }
            } catch (IOException e) {
                throw new PackageInstallException("Failed to create temporary npm config file for custom registry", e);
            }
            args = new ArrayList<>(args);
            args.add(1, "--userconfig");
            args.add(2, customTempConfig.toString());
        }
        try {
            CommandRunner.runCommand(npmPath, baseDir, processTimeout, args.toArray(String[]::new));
        } catch (IOException | InterruptedException e) {
            throw new PackageInstallException(e);
        } finally {
            if (customTempConfig != null) {
                try {
                    Files.deleteIfExists(customTempConfig);
                } catch (IOException e) {
                    log.warn("Failed to delete temporary npm config file: " + customTempConfig, e);
                }
            }
        }
        verifyInstalledPackage(packageName);
    }

    /// Install a package from a local directory that contains a package.json.
    ///
    /// @param localPackageDir path to a directory with package.json
    /// @return the installed package name as read from package.json
    /// @throws PackageInstallException if installation fails
    public String installExtensionFromLocalDirectory(Path localPackageDir)
            throws PackageInstallException, ExtensionLoadException {
        localPackageDir = localPackageDir.toAbsolutePath().normalize();
        if (npmPath == null) {
            throw new PackageInstallException(
                    "npm executable not found or configured, cannot install package in " + localPackageDir);
        }
        if (!Files.isDirectory(localPackageDir) || !Files.isRegularFile(localPackageDir.resolve("package.json"))) {
            throw new PackageInstallException(
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
                    // file: is mandatory so we get an "editable" installation that reflects changes
                    // in the source directory
                    "file:%s".formatted(localPackageDir.toAbsolutePath().toString()));
        } catch (IOException | InterruptedException e) {
            throw new PackageInstallException(e);
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
        } else {
            root = nm.resolve(packageName);
        }
        if (!Files.isDirectory(root)) {
            return null;
        }
        return root;
    }

    /// Parse package.json using Jackson to extract name, version, and ESM entry point.
    private PackageJsonInfo parsePackageJson(Path packageRoot) throws PackageInstallException {
        Path pkgJson = packageRoot.resolve("package.json");
        if (!Files.isRegularFile(pkgJson)) {
            throw new PackageInstallException("No package.json found in package root: " + packageRoot);
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(Files.readAllBytes(pkgJson), new TypeReference<>() {});
        } catch (IOException | JacksonException e) {
            throw new PackageInstallException("Failed to parse package.json at '%s'".formatted(packageRoot), e);
        }
        String name;
        if (root.get("name") instanceof String nameStr) {
            name = nameStr;
        } else {
            throw new PackageInstallException("Package has no name field: " + pkgJson);
        }
        String version;
        if (root.get("version") instanceof String versionStr) {
            version = versionStr;
        } else {
            throw new PackageInstallException("Package has no version field: " + pkgJson);
        }

        String esmEntry;
        Object exports = root.get("exports");
        if (exports instanceof String exportString) {
            esmEntry = exportString;
        } else {
            throw new PackageInstallException("Package has no exports field with a string value: " + pkgJson);
        }
        return new PackageJsonInfo(name, version, packageRoot.resolve(esmEntry));
    }

    /// Get the path to the entry point for the package
    public @Nullable Path getWolpiEntryPoint(String packageName) throws PackageInstallException {
        Path pkg = getPackageRoot(packageName);
        if (pkg == null) {
            throw new PackageInstallException("Package not installed: " + packageName);
        }
        PackageJsonInfo info = parsePackageJson(pkg);
        return info.esmEntryPoint;
    }

    ///  Get the version of the installed package
    public String getVersion(String packageName) throws PackageInstallException {
        Path pkg = getPackageRoot(packageName);
        if (pkg == null) {
            throw new PackageInstallException("Package not installed: " + packageName);
        }
        PackageJsonInfo info = parsePackageJson(pkg);
        return info.version();
    }

    /// Check if the installer supports live reloading of packages.
    ///
    /// This is only the case if the configured npm executable is version 10 or higher. Older
    /// vesions do not create a symbolic link when installing from a local directory, so changes
    /// to the source code are not reflected in the installed package.
    public boolean supportsPackageLiveReload() {
        if (npmPath == null) {
            return false;
        }
        try {
            var version = CommandRunner.runCommand(npmPath, null, Duration.ofSeconds(10), "--version")
                    .trim();
            int major = Integer.parseInt(version.split("\\.")[0]);
            return major >= 10;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to get npm version", e);
        }
    }

    public record PackageJsonInfo(String name, String version, Path esmEntryPoint) {}

    private void verifyInstalledPackage(String packageName) throws ExtensionLoadException {
        Path entryPoint = null;
        Exception cause = null;
        try {
            entryPoint = getWolpiEntryPoint(packageName);
        } catch (PackageInstallException e) {
            cause = e;
        }
        if (entryPoint == null) {
            throw new ExtensionLoadException("Failed to verify installed package: " + packageName, cause);
        }
    }
}
