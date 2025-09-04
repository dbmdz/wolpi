package dev.mdz.iiif.wolpi.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mdz.iiif.wolpi.config.WolpiConfig;
import dev.mdz.iiif.wolpi.config.WolpiConfig.PackagingConfig;
import dev.mdz.iiif.wolpi.testutil.ProcessBuilderMocks;
import dev.mdz.iiif.wolpi.util.CommandRunner;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class PyPiInstallerTest {

  private PyPiInstaller installer;

  @TempDir Path tempDir;

  Path pypiDir;

  @BeforeEach
  void setUp() throws IOException {
    Path pythonPath = tempDir.resolve("python");
    Files.createFile(pythonPath);
    assertThat(pythonPath.toFile().setExecutable(true)).isTrue();
    System.setProperty("PATH", tempDir.toString());
    WolpiConfig config =
        new WolpiConfig(
            tempDir,
            null,
            null,
            null,
            Collections.emptyList(),
            null,
            new PackagingConfig(null, pythonPath, Duration.ofSeconds(5)),
            Collections.emptyMap());
    installer = new PyPiInstaller(config, new ObjectMapper());
    pypiDir = tempDir.resolve("pypi");
    Files.createDirectories(pypiDir);
  }

  @Test
  void install() throws Exception {
    Path distInfoLocation =
        pypiDir.resolve(
            "test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
    String inspectOutput =
        String.format(
            "{"
                + "\"installed\": [{\"metadata\": {\"name\": \"test-package\"}, \"metadata_location\": \"%s\"}]}",
            distInfoLocation);
    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .success()
            .stdoutWhenContains("inspect", inspectOutput);
    try (var _ = builder.build()) {
      Files.createDirectories(distInfoLocation);
      Files.writeString(
          distInfoLocation.resolve("entry_points.txt"),
          "[wolpi]\nmy-ext = my_pkg.main:get_extension");

      installer.install("test-package", "1.2.3", URI.create("https://example.com/simple"));
    }
  }

  @Test
  void installFromLocalDirectory() throws Exception {
    Path packageDir = tempDir.resolve("my-package");
    Files.createDirectories(packageDir);
    Path pyproject = packageDir.resolve("pyproject.toml");
    Files.writeString(pyproject, "[project]\nname = \"my-package\"");

    Path distInfoDir =
        pypiDir.resolve(
            "my-package", "lib", "python3.11", "site-packages", "my_package-0.1.0.dist-info");
    String inspectOutput =
        String.format(
            "{"
                + "\"installed\": [{\"metadata\": {\"name\": \"my-package\"}, \"metadata_location\": \"%s\"}]}",
            distInfoDir);
    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .success()
            .stdoutWhenContains("inspect", inspectOutput);
    try (var _ = builder.build()) {
      Files.createDirectories(distInfoDir);
      Files.writeString(
          distInfoDir.resolve("entry_points.txt"), "[wolpi]\nmy-ext = my_pkg.main:get_extension");

      String packageName = installer.installFromLocalDirectory(packageDir);
      assertThat(packageName).isEqualTo("my-package");
    }
  }

  @Test
  void installFails() {
    assertThatThrownBy(
            () -> {
              var builder =
                  ProcessBuilderMocks.builder()
                      .matchCommandTokenContains("pip")
                      .failure()
                      .stderr("pip install failed");
              try (var _ = builder.build()) {
                installer.install(
                    "test-package", "1.2.3", URI.create("https://example.com/simple"));
              }
            })
        .isInstanceOf(ExtensionLoadException.class)
        .hasMessageContaining("pip")
        .hasMessageContaining("fail");
  }

  @Test
  void pythonNotFound() throws IOException {
    WolpiConfig config =
        new WolpiConfig(
            tempDir,
            null,
            null,
            null,
            Collections.emptyList(),
            null,
            new PackagingConfig(null, null, Duration.ofSeconds(5)),
            Collections.emptyMap());
    try (MockedStatic<CommandRunner> sys = Mockito.mockStatic(CommandRunner.class)) {
      sys.when(() -> CommandRunner.getEnvVar("PATH")).thenReturn("");

      PyPiInstaller installerWithoutPython = new PyPiInstaller(config, new ObjectMapper());
      assertThatThrownBy(
              () ->
                  installerWithoutPython.install(
                      "test-package", "1.2.3", URI.create("https://example.com/simple")))
          .isInstanceOf(ExtensionLoadException.class)
          .hasMessageContaining("Python executable not configured or not found")
          .hasMessageContaining("test-package");
    }
  }

  @Test
  void getEntryPoint() throws Exception {
    Path distInfo =
        pypiDir.resolve(
            "test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
    Files.createDirectories(distInfo);
    Path entryPointsFile = distInfo.resolve("entry_points.txt");
    Files.writeString(entryPointsFile, "[wolpi]\nmy-ext = my_pkg.main:get_extension");

    String inspectOutput =
        String.format(
            """
                {"installed": [{"metadata": {"name": "test-package"}, "metadata_location": "%s"}]}""",
            distInfo);

    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .success()
            .stdoutWhenContains("inspect", inspectOutput);
    try (var _ = builder.build()) {
      PyPiInstaller.EntryPoint entryPoint = installer.getEntryPoint("test-package");
      assertThat(entryPoint.module()).isEqualTo("my_pkg.main");
      assertThat(entryPoint.function()).isEqualTo("get_extension");
    }
  }

  @Test
  void getEntryPoint_missingEntryPointsTxt_throws() throws Exception {
    Path distInfo =
        pypiDir.resolve(
            "test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
    Files.createDirectories(distInfo);
    String inspectOutput =
        String.format(
            """
                {"installed": [{"metadata": {"name": "test-package"}, "metadata_location": "%s"}]}""",
            distInfo);
    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .success()
            .stdoutWhenContains("inspect", inspectOutput);
    try (var _ = builder.build()) {
      assertThatThrownBy(() -> installer.getEntryPoint("test-package"))
          .isInstanceOf(ExtensionLoadException.class)
          .hasMessageContaining("entry_points.txt")
          .hasMessageContaining("Could not find");
    }
  }

  @Test
  void getEntryPoint_noWolpiSection_throws() throws Exception {
    Path distInfo =
        pypiDir.resolve(
            "test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
    Files.createDirectories(distInfo);
    Files.writeString(distInfo.resolve("entry_points.txt"), "[console_scripts]\nfoo = bar:baz");
    String inspectOutput =
        String.format(
            """
                {"installed": [{"metadata": {"name": "test-package"}, "metadata_location": "%s"}]}""",
            distInfo);
    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .success()
            .stdoutWhenContains("inspect", inspectOutput);
    try (var _ = builder.build()) {
      assertThatThrownBy(() -> installer.getEntryPoint("test-package"))
          .isInstanceOf(ExtensionLoadException.class)
          .hasMessageContaining("No 'wolpi' entry point found")
          .hasMessageContaining("test-package");
    }
  }

  @Test
  void getEntryPoint_invalidSpec_throws() throws Exception {
    Path distInfo =
        pypiDir.resolve(
            "test-package", "lib", "python3.11", "site-packages", "test_package-1.2.3.dist-info");
    Files.createDirectories(distInfo);
    Files.writeString(distInfo.resolve("entry_points.txt"), "[wolpi]\nmy-ext = invalidspec");
    String inspectOutput =
        String.format(
            """
                {"installed": [{"metadata": {"name": "test-package"}, "metadata_location": "%s"}]}""",
            distInfo);
    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .success()
            .stdoutWhenContains("inspect", inspectOutput);
    try (var _ = builder.build()) {
      assertThatThrownBy(() -> installer.getEntryPoint("test-package"))
          .isInstanceOf(ExtensionLoadException.class)
          .hasMessageContaining("Invalid wolpi entry point specification")
          .hasMessageContaining("test-package");
    }
  }

  @Test
  void getEntryPoint_invalidInspectJson_throws() {
    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .success()
            .stdoutWhenContains("inspect", "{invalid");
    try (var _ = builder.build()) {
      assertThatThrownBy(() -> installer.getEntryPoint("test-package"))
          .isInstanceOf(ExtensionLoadException.class)
          .hasMessageContaining("Failed to parse pip inspect output");
    }
  }

  @Test
  void getEntryPoint_packageNotFoundInInspect_throws() throws Exception {
    Path otherDist =
        pypiDir.resolve("other", "lib", "python3.11", "site-packages", "other-0.1.0.dist-info");
    Files.createDirectories(otherDist);
    String inspectOutput =
        String.format(
            """
                {"installed": [{"metadata": {"name": "other"}, "metadata_location": "%s"}]}""",
            otherDist);
    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .success()
            .stdoutWhenContains("inspect", inspectOutput);
    try (var _ = builder.build()) {
      assertThatThrownBy(() -> installer.getEntryPoint("test-package"))
          .isInstanceOf(ExtensionLoadException.class)
          .hasMessageContaining("Could not determine install location")
          .hasMessageContaining("test-package");
    }
  }

  @Test
  void getVenvSitePackages() throws Exception {
    Path venvPath = pypiDir.resolve("test-package");
    Path sitePackages = venvPath.resolve("lib").resolve("python3.11").resolve("site-packages");
    Files.createDirectories(sitePackages);

    var builder = ProcessBuilderMocks.builder().matchCommandTokenContains("pip").success();
    try (var _ = builder.build()) {
      installer.ensureVenv("test-package");
      assertThat(installer.getVenvSitePackages("test-package")).isEqualTo(sitePackages);
    }
  }

  @Test
  void getVenvSitePackages_returnsNullWhenVenvMissing() {
    assertThat(installer.getVenvSitePackages("does-not-exist")).isNull();
  }

  @Test
  void getVenvSitePackages_returnsNullWhenNoPythonLib() throws Exception {
    Path venv = pypiDir.resolve("pkg");
    Files.createDirectories(venv.resolve("lib"));
    assertThat(installer.getVenvSitePackages("pkg")).isNull();
  }

  @Test
  void getVersion() {
    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .success()
            .stdoutWhenContains("show", "Name: test-package\nVersion: 9.8.7\nSummary: example");
    try (var _ = builder.build()) {
      String version = installer.getVersion("test-package");
      assertThat(version).isEqualTo("9.8.7");
    }
  }

  @Test
  void getVersion_returnsNullOnError() {
    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .failure()
            .stderr("pip show failed");
    try (var _ = builder.build()) {
      String version = installer.getVersion("test-package");
      assertThat(version).isNull();
    }
  }

  @Test
  void getVersion_returnsNullWhenNoVersionLine() {
    var builder =
        ProcessBuilderMocks.builder()
            .matchCommandTokenContains("pip")
            .success()
            .stdoutWhenContains("show", "Name: test-package\nSummary: example");
    try (var _ = builder.build()) {
      String version = installer.getVersion("test-package");
      assertThat(version).isNull();
    }
  }

  @Test
  void installFromLocalDirectory_missingPyproject_throws() {
    Path dir = pypiDir.resolve("no-pyproject");
    assertThat(dir.toFile().mkdirs()).isTrue();
    assertThatThrownBy(() -> installer.installFromLocalDirectory(dir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pyproject.toml");
  }
}
