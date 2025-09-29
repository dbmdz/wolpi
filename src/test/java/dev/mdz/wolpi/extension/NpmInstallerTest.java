package dev.mdz.wolpi.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.config.WolpiConfig.PackagingConfig;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.extension.util.CommandRunner;
import dev.mdz.wolpi.testutil.ProcessBuilderMocks;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@DisplayName("NpmInstaller")
class NpmInstallerTest {

    private NpmInstaller installer;

    @TempDir
    Path tempDir;

    Path nodeModulesDir;

    @BeforeEach
    void setUp() throws IOException, ExtensionLoadException, PackageInstallException {
        Path npmPath = tempDir.resolve("bin", "npm");
        Files.createDirectories(npmPath.getParent());
        Files.createFile(npmPath);
        assertThat(npmPath.toFile().setExecutable(true)).isTrue();

        WolpiConfig config = new WolpiConfig(
                tempDir,
                null,
                null,
                null,
                Collections.emptyList(),
                null,
                new PackagingConfig(npmPath, null, Duration.ofSeconds(5)),
                Collections.emptyMap());
        installer = new NpmInstaller(config, new ObjectMapper());
        nodeModulesDir = tempDir.resolve("npm", "node_modules");
        Files.createDirectories(nodeModulesDir);
    }

    @Test
    @DisplayName("should install a package from the npm registry")
    void installExtension() throws Exception {
        Path packageDir = nodeModulesDir.resolve("test-package");
        Files.createDirectories(packageDir);
        Files.writeString(
                packageDir.resolve("package.json"),
                """
        {
          "name": "test-package",
          "version": "1.2.3",
          "exports": "dist/index.js"
        }
        """);
        Path installDir = nodeModulesDir.resolve("test-package");
        Files.createDirectories(installDir);
        Files.copy(packageDir.resolve("package.json"), installDir.resolve("package.json"));

        var builder =
                ProcessBuilderMocks.builder().matchCommandTokenContains("npm").success();
        try (var _ = builder.build()) {
            installer.installExtension("test-package", "1.2.3", URI.create("https://registry.npmjs.org"));
        }
    }

    @Test
    @DisplayName("should install a package from a local directory")
    void installExtensionFromLocalDirectory() throws Exception {
        Path packageDir = tempDir.resolve("my-package");
        Files.createDirectories(packageDir);
        Files.writeString(
                packageDir.resolve("package.json"),
                """
        {
          "name": "my-package",
          "version": "0.1.0",
          "exports": "dist/index.js"
        }
        """);
        Path installDir = nodeModulesDir.resolve("my-package");
        Files.createDirectories(installDir);
        Files.copy(packageDir.resolve("package.json"), installDir.resolve("package.json"));

        var builder =
                ProcessBuilderMocks.builder().matchCommandTokenContains("npm").success();
        try (var _ = builder.build()) {
            String packageName = installer.installExtensionFromLocalDirectory(packageDir);
            assertThat(packageName).isEqualTo("my-package");
        }
    }

    @Test
    @DisplayName("should throw an exception if npm install fails")
    void installExtensionFails() {
        assertThatThrownBy(() -> {
                    var builder = ProcessBuilderMocks.builder()
                            .matchCommandTokenContains("npm")
                            .failure()
                            .stderr("npm install failed");
                    try (var _ = builder.build()) {
                        installer.installExtension("test-package", "1.2.3", URI.create("https://registry.npmjs.org"));
                    }
                })
                .isInstanceOf(PackageInstallException.class)
                .hasMessageContaining("npm")
                .hasMessageContaining("fail");
    }

    @Test
    @DisplayName("should throw an exception if npm executable not found")
    void npmExecutableNotFound() throws PackageInstallException {
        WolpiConfig config = new WolpiConfig(
                tempDir,
                null,
                null,
                null,
                Collections.emptyList(),
                null,
                new PackagingConfig(null, null, Duration.ofSeconds(5)),
                Collections.emptyMap());
        try (MockedStatic<CommandRunner> runner = Mockito.mockStatic(CommandRunner.class)) {
            runner.when(() -> CommandRunner.getEnvVar("PATH")).thenReturn("");

            NpmInstaller installerWithoutNode = new NpmInstaller(config, new ObjectMapper());
            assertThatThrownBy(() -> installerWithoutNode.installExtension(
                            "test-package", "1.2.3", URI.create("https://registry.npmjs.org")))
                    .isInstanceOf(PackageInstallException.class)
                    .hasMessageContaining("npm executable not configured");
        }
    }

    @Test
    @DisplayName("should return the package root directory")
    void shouldReturnPackageRoot() throws Exception {
        Path packageDir = nodeModulesDir.resolve("test-package");
        Files.createDirectories(packageDir);

        Path result = installer.getPackageRoot("test-package");
        assertThat(result).isEqualTo(packageDir);
    }

    @Test
    @DisplayName("should return the package root directory for a scoped package")
    void shouldReturnPackageRootForScopedPackage() throws Exception {
        Path scopeDir = nodeModulesDir.resolve("@scope");
        Path packageDir = scopeDir.resolve("test-package");
        Files.createDirectories(packageDir);

        Path result = installer.getPackageRoot("@scope/test-package");
        assertThat(result).isEqualTo(packageDir);
    }

    @Test
    @DisplayName("should throw an exception if scoped package has invalid name")
    void shouldThrowIfScopedPackageHasInvalidName() {
        assertThatThrownBy(() -> installer.getPackageRoot("@scope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid scoped package name");

        assertThatThrownBy(() -> installer.getPackageRoot("@scope/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid scoped package name");
    }

    @Test
    @DisplayName("should return the entry point of an installed package")
    void shouldReturnEntryPointOfInstalledPackage() throws Exception {
        Path packageDir = nodeModulesDir.resolve("test-package");
        Files.createDirectories(packageDir);
        Files.writeString(
                packageDir.resolve("package.json"),
                """
        {
          "name": "test-package",
          "version": "1.2.3",
          "exports": "dist/extension.js"
        }
        """);

        Path entryPoint = installer.getWolpiEntryPoint("test-package");
        assertThat(entryPoint).isEqualTo(packageDir.resolve("dist/extension.js"));
    }

    @Test
    @DisplayName("should throw an exception if package is missing package.json")
    void shouldThrowIfPackageIsMissingPackageJson() throws Exception {
        Path packageDir = nodeModulesDir.resolve("test-package");
        Files.createDirectories(packageDir);

        assertThatThrownBy(() -> installer.getWolpiEntryPoint("test-package"))
                .isInstanceOf(PackageInstallException.class)
                .hasMessageContaining("No package.json found");
    }

    @Test
    @DisplayName("should throw an exception if package.json has no exports field")
    void shouldThrowIfPackageJsonHasNoExportsField() throws Exception {
        Path packageDir = nodeModulesDir.resolve("test-package");
        Files.createDirectories(packageDir);
        Files.writeString(
                packageDir.resolve("package.json"),
                """
        {
          "name": "test-package",
          "version": "1.2.3"
        }
        """);

        assertThatThrownBy(() -> installer.getWolpiEntryPoint("test-package"))
                .isInstanceOf(PackageInstallException.class)
                .hasMessageContaining("no exports field");
    }

    @Test
    @DisplayName("should throw an exception if package has invalid package.json")
    void shouldThrowIfPackageHasInvalidPackageJson() throws Exception {
        Path packageDir = nodeModulesDir.resolve("test-package");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("package.json"), "{invalid json");

        assertThatThrownBy(() -> installer.getWolpiEntryPoint("test-package"))
                .isInstanceOf(PackageInstallException.class)
                .hasMessageContaining("Failed to parse package.json");
    }

    @Test
    @DisplayName("should return the version of an installed package")
    void shouldReturnVersionOfInstalledPackage() throws Exception {
        Path packageDir = nodeModulesDir.resolve("test-package");
        Files.createDirectories(packageDir);
        Files.writeString(
                packageDir.resolve("package.json"),
                """
        {
          "name": "test-package",
          "version": "9.8.7",
          "exports": "dist/index.js"
        }
        """);

        String version = installer.getVersion("test-package");
        assertThat(version).isEqualTo("9.8.7");
    }

    @Test
    @DisplayName("should throw an exception if package not found when getting version")
    void shouldThrowIfPackageNotFoundWhenGettingVersion() throws Exception {
        assertThatThrownBy(() -> installer.getVersion("non-existent-package"))
                .isInstanceOf(PackageInstallException.class)
                .hasMessageContaining("not installed");
    }

    @Test
    @DisplayName("should throw an exception if package.json is missing when getting version")
    void shouldThrowIfPackageJsonIsMissingWhenGettingVersion() throws Exception {
        Path packageDir = nodeModulesDir.resolve("test-package");
        Files.createDirectories(packageDir);

        assertThatThrownBy(() -> installer.getVersion("test-package"))
                .isInstanceOf(PackageInstallException.class)
                .hasMessageContaining("No package.json");
    }

    @Test
    @DisplayName("should throw an exception if package.json has no version field when getting version")
    void shouldThrowIfPackageJsonHasNoVersionFieldWhenGettingVersion() throws Exception {
        Path packageDir = nodeModulesDir.resolve("test-package");
        Files.createDirectories(packageDir);
        Files.writeString(
                packageDir.resolve("package.json"),
                """
        {
          "name": "test-package"
        }
        """);

        assertThatThrownBy(() -> installer.getVersion("test-package"))
                .isInstanceOf(PackageInstallException.class)
                .hasMessageContaining("no version field");
    }

    @Test
    @DisplayName("should throw an exception if directory has no package.json when installing from local directory")
    void shouldThrowIfDirectoryHasNoPackageJsonWhenInstallingFromLocalDirectory() {
        Path dir = tempDir.resolve("no-package-json");
        assertThat(dir.toFile().mkdirs()).isTrue();
        assertThatThrownBy(() -> installer.installExtensionFromLocalDirectory(dir))
                .isInstanceOf(PackageInstallException.class)
                .hasMessageContaining("must exist and contain a package.json");
    }
}
