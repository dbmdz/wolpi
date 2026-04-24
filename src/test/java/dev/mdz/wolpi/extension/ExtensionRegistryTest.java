package dev.mdz.wolpi.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mdz.wolpi.config.ExtensionConfig;
import dev.mdz.wolpi.config.ExtensionConfig.PkgSource;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.config.WolpiConfig.ExtensionDebugConfig;
import dev.mdz.wolpi.extension.PyPiInstaller.EntryPoint;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.extension.model.ExtensionHooks;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
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
        var registry = buildRegistryWithExtensions();
        assertThat(registry.getExtensions()).isEmpty();
    }

    @DisplayName("should load a single JavaScript file")
    @Test
    void shouldLoadSingleJsFile() {
        Path source = Path.of("src/test/resources/test.js");
        var registry = buildRegistryWithExtensions(new ExtensionConfig(source, null, null, Map.of(), false));
        assertThat(registry.getExtensions()).hasSize(1);
        var loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("Test JS File Extension");
    }

    @DisplayName("should load a JavaScript class instance exported as default")
    @Test
    void shouldLoadJsClassInstance() {
        Path source = Path.of("src/test/resources/js-class-instance.js");
        var registry = buildRegistryWithExtensions(new ExtensionConfig(source, null, null, Map.of(), false));
        assertThat(registry.getExtensions()).hasSize(1);
        var loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("Class Instance JS Extension");
        assertThat(registry.getExtensions(ExtensionHooks.RESOLVE)).hasSize(1);
    }

    @DisplayName("should load a single Python file")
    @Test
    void shouldLoadSinglePyFile() {
        Path source = Path.of("src/test/resources/test.py");
        var registry =
                buildRegistryWithExtensions(new ExtensionConfig(source, null, null, Collections.emptyMap(), false));
        assertThat(registry.getExtensions()).hasSize(1);
        var loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("Test PY File Extension");
    }

    @DisplayName("should only register overridden hooks for class-based Python factory files")
    @Test
    void shouldOnlyRegisterOverriddenHooksForClassBasedPyFile() throws IOException {
        var source = writePyExtension("class-based-hooks", """
                from wolpi import WolpiExtension


                class Extension(WolpiExtension):
                    def info(self):
                        return {"name": "Class Based Hooks", "apiVersion": 1, "description": "test"}

                    def cleanup(self):
                        pass

                    def pre_scale(self, image, identifier, image_info, request):
                        return None


                def wolpi_extension():
                    return Extension()
                """);
        var registry = buildRegistryWithExtensions(source);
        assertThat(registry.getExtensions(ExtensionHooks.INFO)).hasSize(1);
        assertThat(registry.getExtensions(ExtensionHooks.CLEANUP)).hasSize(1);
        assertThat(registry.getExtensions(ExtensionHooks.SCALE)).hasSize(1);
        assertThat(registry.getExtensions(ExtensionHooks.CROP)).isEmpty();
        assertThat(registry.getExtensions(ExtensionHooks.PREPROCESS_IMAGE)).isEmpty();
        assertThat(registry.getExtensions(ExtensionHooks.AUTHORIZE)).isEmpty();
    }

    @DisplayName("should register hooks inherited from user-defined Python base classes")
    @Test
    void shouldRegisterHooksInheritedFromUserDefinedPyBaseClass() throws IOException {
        var source = writePyExtension("base-class-hooks", """
                from wolpi import WolpiExtension


                class BaseExtension(WolpiExtension):
                    def pre_scale(self, image, identifier, image_info, request):
                        return None


                class Extension(BaseExtension):
                    def info(self):
                        return {"name": "Base Class Hooks", "apiVersion": 1, "description": "test"}

                    def cleanup(self):
                        pass


                def wolpi_extension():
                    return Extension()
                """);
        var registry = buildRegistryWithExtensions(source);
        assertThat(registry.getExtensions(ExtensionHooks.SCALE)).hasSize(1);
        assertThat(registry.getExtensions(ExtensionHooks.CROP)).isEmpty();
    }

    @DisplayName("should reject class-based Python files without cleanup hook")
    @Test
    void shouldRejectClassBasedPyFileWithoutCleanup() throws IOException {
        var registry = buildRegistryWithExtensions();
        var source = writePyExtension("missing-cleanup", """
                class Extension:
                    def info(self):
                        return {"name": "Missing Cleanup", "apiVersion": 1, "description": "test"}


                def wolpi_extension():
                    return Extension()
                """);
        assertThatThrownBy(() -> registry.loadExtension(source))
                .isInstanceOf(ExtensionLoadException.class)
                .hasMessageContaining("mandatory 'cleanup'");
    }

    @DisplayName("should reject class-based Python files without info hook")
    @Test
    void shouldRejectClassBasedPyFileWithoutInfo() throws IOException {
        var registry = buildRegistryWithExtensions();
        var source = writePyExtension("missing-info", """
                class Extension:
                    def cleanup(self):
                        pass


                def wolpi_extension():
                    return Extension()
                """);
        assertThatThrownBy(() -> registry.loadExtension(source))
                .isInstanceOf(ExtensionLoadException.class)
                .hasMessageContaining("mandatory 'info'");
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
        var registry = buildRegistryWithExtensions(new ExtensionConfig(source, null, null, Map.of(), false));
        assertThat(registry.getExtensions()).hasSize(1);
        var loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("JavaScript Test Extension");

        // Install from npm
        registry = buildRegistryWithExtensions(
                new ExtensionConfig(null, new PkgSource("js-extension", "1.0.0", null, null), null, Map.of(), false));
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
        var registry = buildRegistryWithExtensions(new ExtensionConfig(source, null, null, Map.of(), false));
        assertThat(registry.getExtensions()).hasSize(1);
        var loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("Test Python Extension");

        // Instal from PyPI
        registry = buildRegistryWithExtensions(
                new ExtensionConfig(null, null, new PkgSource("py-extension", "1.0.0", null, null), Map.of(), false));
        assertThat(registry.getExtensions()).hasSize(1);
        loadedExtension = registry.getExtensions(ExtensionHooks.AUTHORIZE).getFirst();
        assertThat(loadedExtension.extensionInfo().name()).isEqualTo("Test Python Extension");
    }

    @DisplayName("should list each extension once per hook and maintain their original order even after reload")
    @Test
    void shouldListEachExtensionOnlyOnce(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path tempSource = tempDir.resolve("test.py");
        Files.copy(Path.of("src/test/resources/test.py"), tempSource);
        ExtensionConfig pyExtentionConfig = new ExtensionConfig(tempSource, null, null, Collections.emptyMap(), true);
        var registry = buildRegistryWithExtensions(
                pyExtentionConfig,
                new ExtensionConfig(Path.of("src/test/resources/test.js"), null, null, Map.of(), false));
        assertThat(registry.getExtensions()).hasSize(2);
        List<LoadedExtension> authorizeExtensions = registry.getExtensions(ExtensionHooks.AUTHORIZE);
        assertThat(authorizeExtensions)
                .hasSize(2)
                .satisfiesExactly(
                        ext -> assertThat(ext.extensionInfo().name()).isEqualTo("Test PY File Extension"),
                        ext -> assertThat(ext.extensionInfo().name()).isEqualTo("Test JS File Extension"));
        List<LoadedExtension> resolveExtensions = registry.getExtensions(ExtensionHooks.RESOLVE);
        assertThat(resolveExtensions).isEmpty();

        var reloaded = new CountDownLatch(1);
        registry.addReloadCallback(pyExtentionConfig, _ -> reloaded.countDown());
        Files.writeString(pyExtentionConfig.path(), """

def resolve(self, identifier, etag, last_modified):
  log_hook_call("resolve")
""", StandardOpenOption.APPEND);
        assertThat(reloaded.await(10, TimeUnit.SECONDS)).isTrue();
        authorizeExtensions = registry.getExtensions(ExtensionHooks.AUTHORIZE);
        assertThat(authorizeExtensions)
                .hasSize(2)
                .satisfiesExactly(
                        ext -> assertThat(ext.extensionInfo().name()).isEqualTo("Test PY File Extension"),
                        ext -> assertThat(ext.extensionInfo().name()).isEqualTo("Test JS File Extension"));
        resolveExtensions = registry.getExtensions(ExtensionHooks.RESOLVE);
        assertThat(resolveExtensions)
                .hasSize(1)
                .satisfiesExactly(ext -> assertThat(ext.extensionInfo().name()).isEqualTo("Test PY File Extension"));
    }

    private ExtensionRegistry buildRegistryWithExtensions(ExtensionConfig... exts) {
        WolpiConfig wolpiConfig = new WolpiConfig(
                Path.of("/data"),
                null,
                null,
                null,
                null,
                null,
                List.of(exts),
                null,
                null,
                new ExtensionDebugConfig(false, "localhost", 4711, false, false),
                null,
                null);
        return new ExtensionRegistry(
                wolpiConfig,
                pyPiInstaller,
                npmInstaller,
                mock(GenericKeyedObjectPool.class),
                new GraalContextSupplier(wolpiConfig),
                new GuestContextFactory(buildProperties, httpClient, Arena.ofAuto(), null, null));
    }

    private ExtensionConfig writePyExtension(String name, String source) throws IOException {
        Path path = tempDir.resolve("%s.py".formatted(name));
        Files.writeString(path, source);
        return new ExtensionConfig(path, null, null, Map.of(), false);
    }
}
