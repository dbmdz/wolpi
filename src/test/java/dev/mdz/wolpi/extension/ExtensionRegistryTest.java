package dev.mdz.wolpi.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.mdz.wolpi.config.ExtensionConfig;
import dev.mdz.wolpi.config.ExtensionConfig.PkgSource;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.config.WolpiConfig.ExtensionDebugConfig;
import dev.mdz.wolpi.extension.PyPiInstaller.EntryPoint;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.extension.model.ExtensionHooks;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.info.BuildProperties;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExtensionRegistry")
class ExtensionRegistryTest {

    @Mock
    private BuildProperties buildProperties;

    @Mock
    private HttpClient httpClient;

    @Mock
    private NpmInstaller npmInstaller;

    @Mock
    private PyPiInstaller pyPiInstaller;

    @TempDir
    private Path tempDir;

    @DisplayName("should not load extensions when config is empty")
    @Test
    void shouldNotLoadExtensionsWhenConfigIsEmpty() {
        var registry = buildRegistryWithExtension(null, null, null, null);
        assertThat(registry.getExtensions()).isEmpty();
    }

    @DisplayName("should load a single JavaScript file")
    @Test
    void shouldLoadSingleJsFile() throws IOException {
        Path source = Path.of("src/test/resources/test.js");
        var registry = buildRegistryWithExtension(source, null, null, Map.of());
        assertThat(registry.getExtensions()).hasSize(1);
        var loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("Test JS File Extension");
    }

    @DisplayName("should load a single Python file")
    @Test
    void shouldLoadSinglePyFile() throws IOException {
        Path source = Path.of("src/test/resources/test.py");
        var registry = buildRegistryWithExtension(source, null, null, Collections.emptyMap());
        assertThat(registry.getExtensions()).hasSize(1);
        var loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("Test PY File Extension");
    }

    @DisplayName("should load a JavaScript package")
    @Test
    void shouldLoadJsPackage() throws IOException, ExtensionLoadException, PackageInstallException {
        Path source = Path.of("src/test/resources/js-extension");
        Path target = tempDir.resolve("js-extension");
        Files.createDirectories(target);
        Files.walk(source).filter(s -> s != source).forEach(s -> {
            try {
                Files.copy(s, target.resolve(source.relativize(s)));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        when(npmInstaller.installExtensionFromLocalDirectory(source)).thenReturn("js-extension");
        when(npmInstaller.getWolpiEntryPoint("js-extension")).thenReturn(target.resolve("index.js"));
        when(npmInstaller.getVersion("js-extension")).thenReturn("1.0.0");

        // Install from local path
        var registry = buildRegistryWithExtension(source, null, null, Map.of());
        assertThat(registry.getExtensions()).hasSize(1);
        var loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("JavaScript Test Extension");

        // Install from npm
        registry = buildRegistryWithExtension(null, new PkgSource("js-extension", "1.0.0", null), null, Map.of());
        assertThat(registry.getExtensions()).hasSize(1);
        loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("JavaScript Test Extension");
    }

    @DisplayName("should load a Python package")
    @Test
    void shouldLoadPyPackage() throws IOException, ExtensionLoadException, PackageInstallException {
        // Create venv structure
        Path venvPath = tempDir.resolve("venv");
        Path binPath = venvPath.resolve("bin");
        Files.createDirectories(binPath);
        Path graalPy = binPath.resolve("graalpy");
        Files.createFile(graalPy);
        graalPy.toFile().setExecutable(true);
        Path libPath = venvPath.resolve("lib/python3.13/site-packages");
        Files.createDirectories(libPath);

        // "Install" extension to venv
        Path source = Path.of("src/test/resources/py-extension");
        Files.walk(source).filter(s -> s != source).forEach(s -> {
            try {
                Files.copy(s, libPath.resolve(source.relativize(s)));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        when(pyPiInstaller.installExtensionFromLocalDirectory(source, false)).thenReturn("py-extension");
        when(pyPiInstaller.getVenvSitePackages("py-extension")).thenReturn(libPath);
        when(pyPiInstaller.getWolpiEntryPoint("py-extension"))
                .thenReturn(new EntryPoint("py_extension", "wolpi_extension"));

        // Install from local path
        var registry = buildRegistryWithExtension(source, null, null, Map.of());
        assertThat(registry.getExtensions()).hasSize(1);
        var loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("Test Python Extension");

        // Instal from PyPI
        registry = buildRegistryWithExtension(null, null, new PkgSource("py-extension", "1.0.0", null), Map.of());
        assertThat(registry.getExtensions()).hasSize(1);
        loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("Test Python Extension");
    }

    private ExtensionRegistry buildRegistryWithExtension(
            Path path, PkgSource npm, PkgSource pypi, Map<String, Object> cfg) {
        List<ExtensionConfig> exts = new ArrayList<>();
        if (path != null || npm != null || pypi != null) {
            exts.add(new ExtensionConfig(path, npm, pypi, cfg, false));
        }
        WolpiConfig wolpiConfig = new WolpiConfig(
                Path.of("/data"),
                null,
                null,
                null,
                null,
                null,
                exts,
                null,
                new ExtensionDebugConfig(false, "localhost", 4711, false, false),
                null,
                null);
        return new ExtensionRegistry(
                wolpiConfig,
                httpClient,
                pyPiInstaller,
                npmInstaller,
                buildProperties,
                null,
                new GraalContextSupplier(wolpiConfig),
                null);
    }
}
