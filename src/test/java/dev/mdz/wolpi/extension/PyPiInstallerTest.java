package dev.mdz.wolpi.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mdz.wolpi.config.ExtensionConfig.IndexAuth;
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
import tools.jackson.databind.json.JsonMapper;

@DisplayName("PyPiInstaller")
class PyPiInstallerTest {

    private PyPiInstaller installer;

    @TempDir
    Path tempDir;

    Path pypiDir;

    @BeforeEach
    void setUp() throws IOException {
        Path pythonPath = tempDir.resolve("python");
        Files.createFile(pythonPath);
        assertThat(pythonPath.toFile().setExecutable(true)).isTrue();
        System.setProperty("PATH", tempDir.toString());
        WolpiConfig config = new WolpiConfig(
                tempDir,
                null,
                null,
                null,
                null,
                null,
                Collections.emptyList(),
                null,
                null,
                null,
                new PackagingConfig(null, pythonPath, Duration.ofSeconds(5)),
                Collections.emptyMap());
        installer = new PyPiInstaller(config, new JsonMapper());
        pypiDir = tempDir.resolve("pypi");
        Files.createDirectories(pypiDir);
    }

    @Test
    @DisplayName("should install a package from PyPI")
    void installExtension() throws Exception {
        Path distInfoLocation =
                pypiDir.resolve("test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
        String inspectOutput = String.format(
                "{" + "\"installed\": [{\"metadata\": {\"name\": \"test-package\"}, \"metadata_location\": \"%s\"}]}",
                distInfoLocation);
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("inspect", inspectOutput);
        try (var _ = builder.build()) {
            Files.createDirectories(distInfoLocation);
            Files.writeString(
                    distInfoLocation.resolve("entry_points.txt"), "[wolpi]\nmy-ext = my_pkg.main:get_extension");

            installer.installExtension("test-package", "1.2.3", URI.create("https://example.com/simple"), null);
        }
    }

    @Test
    @DisplayName("should install a package from a local directory")
    void installExtensionFromLocalDirectory() throws Exception {
        Path packageDir = tempDir.resolve("my-package");
        Files.createDirectories(packageDir);
        Path pyproject = packageDir.resolve("pyproject.toml");
        Files.writeString(pyproject, "[project]\nname = \"my-package\"\nversion=\"0.1.0\"\n");

        Path distInfoDir =
                pypiDir.resolve("my-package", "lib", "python3.11", "site-packages", "my_package-0.1.0.dist-info");
        String inspectOutput = String.format(
                "{" + "\"installed\": [{\"metadata\": {\"name\": \"my-package\"}, \"metadata_location\": \"%s\"}]}",
                distInfoDir);
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("inspect", inspectOutput);
        try (var _ = builder.build()) {
            Files.createDirectories(distInfoDir);
            Files.writeString(distInfoDir.resolve("entry_points.txt"), "[wolpi]\nmy-ext = my_pkg.main:get_extension");

            String packageName = installer.installExtensionFromLocalDirectory(packageDir, false);
            assertThat(packageName).isEqualTo("my-package");
        }
    }

    @Test
    @DisplayName("should throw an exception if installation fails")
    void installExtensionFails() {
        assertThatThrownBy(() -> {
                    var builder = ProcessBuilderMocks.builder()
                            .matchCommandTokenContains("pip")
                            .failure()
                            .stderr("pip install failed");
                    try (var _ = builder.build()) {
                        installer.installExtension(
                                "test-package", "1.2.3", URI.create("https://example.com/simple"), null);
                    }
                })
                .isInstanceOf(PackageInstallException.class)
                .hasMessageContaining("pip")
                .hasMessageContaining("fail");
    }

    @Test
    @DisplayName("should throw an exception if python is not configured")
    void pythonNotFound() throws IOException {
        WolpiConfig config = new WolpiConfig(
                tempDir,
                null,
                null,
                null,
                null,
                null,
                Collections.emptyList(),
                null,
                null,
                null,
                new PackagingConfig(null, null, Duration.ofSeconds(5)),
                Collections.emptyMap());
        try (MockedStatic<CommandRunner> sys = Mockito.mockStatic(CommandRunner.class)) {
            sys.when(() -> CommandRunner.getEnvVar("PATH")).thenReturn("");

            PyPiInstaller installerWithoutPython = new PyPiInstaller(config, new JsonMapper());
            assertThatThrownBy(() -> installerWithoutPython.installExtension(
                            "test-package", "1.2.3", URI.create("https://example.com/simple"), null))
                    .isInstanceOf(PackageInstallException.class)
                    .hasMessageContaining("Python executable not configured or not found")
                    .hasMessageContaining("test-package");
        }
    }

    @Test
    @DisplayName("should retrieve entry point from installed package")
    void getWolpiEntryPoint() throws Exception {
        Path distInfo =
                pypiDir.resolve("test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
        Files.createDirectories(distInfo);
        Path entryPointsFile = distInfo.resolve("entry_points.txt");
        Files.writeString(entryPointsFile, "[wolpi]\nmy-ext = my_pkg.main:get_extension");

        String inspectOutput = String.format("""
                {"installed": [{"metadata": {"name": "test-package"}, "metadata_location": "%s"}]}""", distInfo);

        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("inspect", inspectOutput);
        try (var _ = builder.build()) {
            PyPiInstaller.EntryPoint entryPoint = installer.getWolpiEntryPoint("test-package");
            assertThat(entryPoint.module()).isEqualTo("my_pkg.main");
            assertThat(entryPoint.function()).isEqualTo("get_extension");
        }
    }

    @Test
    @DisplayName("should throw an exception when entry_points.txt is missing")
    void getEntryPoint_missingWolpiEntryPointsTxt_throws() throws Exception {
        Path distInfo =
                pypiDir.resolve("test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
        Files.createDirectories(distInfo);
        String inspectOutput = String.format("""
                {"installed": [{"metadata": {"name": "test-package"}, "metadata_location": "%s"}]}""", distInfo);
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("inspect", inspectOutput);
        try (var _ = builder.build()) {
            assertThatThrownBy(() -> installer.getWolpiEntryPoint("test-package"))
                    .isInstanceOf(ExtensionLoadException.class)
                    .hasMessageContaining("entry_points.txt")
                    .hasMessageContaining("Could not find");
        }
    }

    @Test
    @DisplayName("should throw an exception when wolpi entry points section is missing")
    void shouldThrowWhenWolpiEntryPointsSectionIsMissing() throws Exception {
        Path distInfo =
                pypiDir.resolve("test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
        Files.createDirectories(distInfo);
        Files.writeString(distInfo.resolve("entry_points.txt"), "[console_scripts]\nfoo = bar:baz");
        String inspectOutput = String.format("""
                {"installed": [{"metadata": {"name": "test-package"}, "metadata_location": "%s"}]}""", distInfo);
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("inspect", inspectOutput);
        try (var _ = builder.build()) {
            assertThatThrownBy(() -> installer.getWolpiEntryPoint("test-package"))
                    .isInstanceOf(ExtensionLoadException.class)
                    .hasMessageContaining("No 'wolpi' entry point found")
                    .hasMessageContaining("test-package");
        }
    }

    @Test
    @DisplayName("should throw an exception when wolpi entry point spec is invalid")
    void shouldThrowWhenWolpiEntryPointSpecIsInvalid() throws Exception {
        Path distInfo =
                pypiDir.resolve("test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
        Files.createDirectories(distInfo);
        Files.writeString(distInfo.resolve("entry_points.txt"), "[wolpi]\nmy-ext = invalidspec");
        String inspectOutput = String.format("""
                {"installed": [{"metadata": {"name": "test-package"}, "metadata_location": "%s"}]}""", distInfo);
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("inspect", inspectOutput);
        try (var _ = builder.build()) {
            assertThatThrownBy(() -> installer.getWolpiEntryPoint("test-package"))
                    .isInstanceOf(ExtensionLoadException.class)
                    .hasMessageContaining("Invalid wolpi entry point specification")
                    .hasMessageContaining("test-package");
        }
    }

    @Test
    @DisplayName("should throw an exception when pip inspect output fails to parse")
    void shouldThrowWhenPipInspectOutputFailsToParse() {
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("inspect", "{invalid");
        try (var _ = builder.build()) {
            assertThatThrownBy(() -> installer.getWolpiEntryPoint("test-package"))
                    .isInstanceOf(ExtensionLoadException.class)
                    .hasMessageContaining("Failed to parse pip inspect output");
        }
    }

    @Test
    @DisplayName("should throw an exception when package not found in pip inspect output")
    void shouldThrowWhenPackageNotFoundInPipInspectOutput() throws Exception {
        Path otherDist = pypiDir.resolve("other", "lib", "python3.11", "site-packages", "other-0.1.0.dist-info");
        Files.createDirectories(otherDist);
        String inspectOutput = String.format("""
                {"installed": [{"metadata": {"name": "other"}, "metadata_location": "%s"}]}""", otherDist);
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("inspect", inspectOutput);
        try (var _ = builder.build()) {
            assertThatThrownBy(() -> installer.getWolpiEntryPoint("test-package"))
                    .isInstanceOf(ExtensionLoadException.class)
                    .hasMessageContaining("Could not determine install location")
                    .hasMessageContaining("test-package");
        }
    }

    @Test
    @DisplayName("should retrieve the site-packages directory of a venv")
    void shouldRetrieveSitePackagesDirectoryOfVenv() throws Exception {
        Path venvPath = pypiDir.resolve("test-package");
        Path sitePackages = venvPath.resolve("lib").resolve("python3.11").resolve("site-packages");
        Files.createDirectories(sitePackages);

        var builder =
                ProcessBuilderMocks.builder().matchCommandTokenContains("pip").success();
        try (var _ = builder.build()) {
            installer.ensureVenv("test-package");
            assertThat(installer.getVenvSitePackages("test-package")).isEqualTo(sitePackages);
        }
    }

    @Test
    @DisplayName("should return null when venv is missing or incomplete")
    void shouldReturnNullWhenVenvIsMissingOrIncomplete() {
        assertThat(installer.getVenvSitePackages("does-not-exist")).isNull();
    }

    @Test
    @DisplayName("should return null when python lib dir is missing")
    void shouldReturnNullWhenPythonLibDirIsMissing() throws Exception {
        Path venv = pypiDir.resolve("pkg");
        Files.createDirectories(venv);
        assertThat(installer.getVenvSitePackages("pkg")).isNull();
    }

    @Test
    @DisplayName("should return null when site-packages dir is missing")
    void shouldReturnNullWhenSitePackagesDirIsMissing() throws IOException {
        Path venv = pypiDir.resolve("pkg");
        Files.createDirectories(venv.resolve("lib"));
        String version = installer.getVersion("test-package");
        assertThat(version).isNull();
    }

    @Test
    @DisplayName("should return null on pip error when getting version")
    void shouldReturnNullOnPipErrorWhenGettingVersion() {
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .failure()
                .stderr("pip show failed");
        try (var _ = builder.build()) {
            String version = installer.getVersion("test-package");
            assertThat(version).isNull();
        }
    }

    @Test
    @DisplayName("should return null when no Version line in pip show output")
    void shouldReturnNullWhenNoVersionLineInPipShowOutput() {
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("show", "Name: test-package\nSummary: example");
        try (var _ = builder.build()) {
            String version = installer.getVersion("test-package");
            assertThat(version).isNull();
        }
    }

    @Test
    @DisplayName("should throw an exception when pyproject.toml is missing when installing from local directory")
    void shouldThrowWhenPyprojectTomlIsMissingWhenInstallingFromLocalDirectory() {
        Path dir = pypiDir.resolve("no-pyproject");
        assertThat(dir.toFile().mkdirs()).isTrue();
        assertThatThrownBy(() -> installer.installExtensionFromLocalDirectory(dir, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pyproject.toml");
    }

    @Test
    @DisplayName("should install package with custom index and auth credentials")
    void shouldInstallPackageWithCustomIndexAndAuth() throws Exception {
        Path distInfoLocation =
                pypiDir.resolve("test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
        String inspectOutput = String.format(
                "{" + "\"installed\": [{\"metadata\": {\"name\": \"test-package\"}, \"metadata_location\": \"%s\"}]}",
                distInfoLocation);
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("inspect", inspectOutput);
        try (var _ = builder.build()) {
            Files.createDirectories(distInfoLocation);
            Files.writeString(
                    distInfoLocation.resolve("entry_points.txt"), "[wolpi]\nmy-ext = my_pkg.main:get_extension");

            var auth = new IndexAuth("testuser", "testpass", null);
            installer.install("test-package", "1.2.3", URI.create("https://example.com/simple"), auth, false);
        }
    }

    @Test
    @DisplayName("should install package without auth when credentials not provided")
    void shouldInstallPackageWithoutAuthWhenCredentialsNotProvided() throws Exception {
        Path distInfoLocation =
                pypiDir.resolve("test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
        String inspectOutput = String.format(
                "{" + "\"installed\": [{\"metadata\": {\"name\": \"test-package\"}, \"metadata_location\": \"%s\"}]}",
                distInfoLocation);
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("inspect", inspectOutput);
        try (var _ = builder.build()) {
            Files.createDirectories(distInfoLocation);
            Files.writeString(
                    distInfoLocation.resolve("entry_points.txt"), "[wolpi]\nmy-ext = my_pkg.main:get_extension");

            installer.install("test-package", "1.2.3", URI.create("https://example.com/simple"), null, false);
        }
    }

    @Test
    @DisplayName("should skip installation if package version already installed")
    void shouldSkipInstallationIfVersionAlreadyInstalled() throws Exception {
        Path distInfoLocation =
                pypiDir.resolve("test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
        String inspectOutput = String.format(
                "{" + "\"installed\": [{\"metadata\": {\"name\": \"test-package\"}, \"metadata_location\": \"%s\"}]}",
                distInfoLocation);
        var builder = ProcessBuilderMocks.builder()
                .matchCommandTokenContains("pip")
                .success()
                .stdoutWhenContains("show", "Version: 1.2.3\nName: test-package")
                .stdoutWhenContains("inspect", inspectOutput);
        try (var _ = builder.build()) {
            Files.createDirectories(distInfoLocation);
            Files.writeString(
                    distInfoLocation.resolve("entry_points.txt"), "[wolpi]\nmy-ext = my_pkg.main:get_extension");

            // First call should trigger installation
            Path result1 = installer.install("test-package", "1.2.3", null, null, false);
            // Second call should skip installation because version matches
            Path result2 = installer.install("test-package", "1.2.3", null, null, false);
            assertThat(result1).isEqualTo(result2);
        }
    }
}
