package dev.mdz.wolpi.extension;

import static dev.mdz.wolpi.testutil.WolpiAssertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VipsOption;
import dev.mdz.wolpi.config.ExtensionConfig;
import dev.mdz.wolpi.config.IIIFConfig;
import dev.mdz.wolpi.config.IIIFConfig.Limits;
import dev.mdz.wolpi.config.IIIFConfig.ScalingFeature;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.config.WolpiConfig.CacheControlHeaders;
import dev.mdz.wolpi.config.WolpiConfig.ExtensionDebugConfig;
import dev.mdz.wolpi.config.WolpiConfig.ExtensionPoolConfig;
import dev.mdz.wolpi.config.WolpiConfig.ExtensionRuntimeConfig;
import dev.mdz.wolpi.config.WolpiConfig.PackagingConfig;
import dev.mdz.wolpi.exceptions.ExtensionExecutionException;
import dev.mdz.wolpi.exceptions.HttpStatusException;
import dev.mdz.wolpi.extension.PyPiInstaller.EntryPoint;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.extension.model.ExtensionHooks;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.iiif.ImageRequestParser;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.iiif.model.ImageRequest;
import dev.mdz.wolpi.metrics.WolpiMetrics;
import dev.mdz.wolpi.model.BinaryResolvedImage;
import dev.mdz.wolpi.model.CustomSourceResolvedImage;
import dev.mdz.wolpi.model.EncodedImage;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSize;
import dev.mdz.wolpi.model.SourceNotModified;
import dev.mdz.wolpi.testutil.VImageHelpers;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.awt.Color;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.info.BuildProperties;

@DisplayName("ExtensionRuntime")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
public class ExtensionRuntimeTest {

    // Reuse installed extensions between test invocations
    @TempDir
    static Path tempDir;

    @Mock
    private BuildProperties buildProperties;

    @Mock
    private HttpClient httpClient;

    @Mock
    private NpmInstaller npmInstaller;

    @Mock
    private PyPiInstaller pyPiInstaller;

    private MeterRegistry meterRegistry;
    private GraalContextSupplier graalContextSupplier;
    private KeyedObjectPool<LoadedExtension, RuntimeContext> contextPool;

    private final ThreadPoolExecutor threadPool =
            new ThreadPoolExecutor(4, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread t = new Thread(runnable);
                    t.setName("extension-runtime-thread-" + t.threadId());
                    return t;
                }
            });

    private WolpiConfig config;
    private Arena testArena;

    @BeforeAll
    static void beforeAll() throws IOException {
        // Create a fake venv with python extension package installed
        Path venvPath = tempDir.resolve("venv");
        Path binPath = venvPath.resolve("bin");
        Files.createDirectories(binPath);
        Path graalPy = binPath.resolve("graalpy");
        Files.createFile(graalPy);
        graalPy.toFile().setExecutable(true);
        Path libPath = venvPath.resolve("lib/python3.11/site-packages");
        Files.createDirectories(libPath);
        Path source = Path.of("src/test/resources/py-extension/py_extension");
        Path destination = libPath.resolve("py_extension");
        Files.walk(source).forEach(s -> {
            try {
                Path target = destination.resolve(source.relativize(s));
                if (Files.isDirectory(s)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(s, target);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @BeforeEach
    void setUp() throws ExtensionLoadException, PackageInstallException {
        lenient().when(buildProperties.getVersion()).thenReturn("0.1.0");
        testArena = Arena.ofConfined();

        // Create new MeterRegistry
        this.meterRegistry = new SimpleMeterRegistry();

        // Create minimal config to initialize registry
        config = new WolpiConfig(
                tempDir,
                null,
                null,
                null,
                mock(IIIFConfig.class),
                mock(CacheControlHeaders.class),
                new ArrayList<>(),
                mock(ExtensionRuntimeConfig.class),
                mock(ExtensionPoolConfig.class),
                new ExtensionDebugConfig(false, "localhost", 4711, false, false),
                mock(PackagingConfig.class),
                Map.of());
        if (graalContextSupplier == null) {
            graalContextSupplier = new GraalContextSupplier(config);
        }
        contextPool = new GenericKeyedObjectPool<>(
                new RuntimeContextPooledObjectFactory(graalContextSupplier), new GenericKeyedObjectPoolConfig<>() {
                    {
                        setMinIdlePerKey(0);
                        setMaxIdlePerKey(2);
                        setMaxTotalPerKey(4);
                        setTimeBetweenEvictionRuns(Duration.ofMillis(10));
                        setMinEvictableIdleDuration(Duration.ofMillis(500));
                        setJmxEnabled(false);
                    }
                });

        Path pyExtPath = Path.of("src/test/resources/py-extension");
        Path pySitePkgPath = tempDir.resolve("venv/lib/python3.11/site-packages");
        lenient()
                .when(pyPiInstaller.installExtensionFromLocalDirectory(pyExtPath, false))
                .thenReturn("py-extension");
        lenient().when(pyPiInstaller.getVenvSitePackages("py-extension")).thenReturn(pySitePkgPath);
        lenient()
                .when(pyPiInstaller.getWolpiEntryPoint("py-extension"))
                .thenReturn(new EntryPoint("py_extension", "wolpi_extension"));
    }

    @AfterEach
    void tearDown() {
        if (testArena != null) {
            testArena.close();
        }
        try {
            contextPool.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to close context pool", e);
        }
    }

    @AfterAll
    void afterAll() throws Exception {
        threadPool.shutdownNow();
        if (graalContextSupplier != null) {
            graalContextSupplier.resetEngine();
        }
    }

    @Nested
    @DisplayName("Authorization Tests")
    class AuthorizationTests {

        @Test
        @DisplayName("Should allow access when no auth extensions are installed")
        void shouldAllowAccessWithNoAuthExtensions() {
            try (ExtensionRuntime runtime = getRuntimeWithExtensions()) {
                assertThat(runtime.authorize("this-should-go-through", Map.of(), "127.0.0.1"))
                        .isTrue();
            }
        }

        @ParameterizedTest(name = "authorize(id={0}) == {1}?")
        @CsvSource({
            "allowed-id,true",
            "allowed-by-one-but-forbidden-by-other,false",
            "py-pkg-id,false",
            "completely-unknown-id,false"
        })
        @DisplayName("Should use auth extension to authorize requests by identifier")
        void shouldUseAuthExtensionToAuthorizeById(String idToAuthorize, boolean expected) {
            var exts = List.of(
                    getTestAuthExtension(TestExtensionType.PY_SINGLE, List.of("allowed-id"), null, null, null),
                    getTestAuthExtension(
                            TestExtensionType.JS,
                            List.of("allowed-id", "allowed-by-one-but-forbidden-by-other"),
                            null,
                            null,
                            null),
                    getTestAuthExtension(TestExtensionType.PY_PKG, null, List.of("py-pkg-id"), null, null));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                assertThat(runtime.authorize(idToAuthorize, Map.of(), "127.0.0.1"))
                        .isEqualTo(expected);
            }
        }

        @ParameterizedTest
        @EnumSource(TestExtensionType.class)
        @DisplayName("Should use auth extension to authorize requests by headers")
        void shouldUseAuthExtensionToAuthorizeByHeaders(TestExtensionType extType) {
            var exts = List.of(getTestAuthExtension(extType, null, null, null, Map.of("X-Auth", "allowed")));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                assertThat(runtime.authorize("someId", Map.of("X-Auth", List.of("allowed")), "127.0.0.1"))
                        .isTrue();
                assertThat(runtime.authorize("someId", Map.of("X-Auth", List.of("forbidden")), "127.0.0.1"))
                        .isFalse();
                assertThat(runtime.authorize("someId", Map.of(), "127.0.0.1")).isFalse();
            }
        }

        @ParameterizedTest
        @EnumSource(TestExtensionType.class)
        @DisplayName("Should use auth extension to authorize requests by client IP")
        void shouldUseAuthExtensionToAuthorizeByClientIp(TestExtensionType extensionType) {
            var exts = List.of(getTestAuthExtension(
                    extensionType,
                    null,
                    null,
                    List.of("123.213.132.123", "4dd1:0a53:b5ac:f377:cf62:54bb:1126:a180"),
                    null));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                assertThat(runtime.authorize("someId", Map.of(), "127.0.0.1")).isFalse();
                assertThat(runtime.authorize("someId", Map.of(), "123.213.132.123"))
                        .isTrue();
                assertThat(runtime.authorize("someId", Map.of(), "4dd1:0a53:b5ac:f377:cf62:54bb:1126:a180"))
                        .isTrue();
            }
        }

        @ParameterizedTest
        @CsvSource({"js,single", "py,single", "js,multi", "py,single"})
        @DisplayName("Should raise an HttpStatusError if an extension raises an HttpStatusError during authorization")
        void shouldRaiseHttpStatusErrorOnAuthorizationError(String lang, String cardinality) {
            List<ExtensionConfig> exts = new ArrayList<>();
            exts.add(getTestAuthExtension(TestExtensionType.JS, null, null, null, Map.of()));
            exts.add(getTestAuthExtension(TestExtensionType.PY_SINGLE, null, null, null, Map.of()));
            if (cardinality.equals("single")) {
                exts.remove(lang.equals("js") ? 1 : 0);
            }
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                String ident = "%s-raise-http-418".formatted(lang);
                Assertions.assertThatThrownBy(() -> runtime.authorize(ident, Map.of(), "127.0.0.1"))
                        .isInstanceOf(HttpStatusException.class)
                        .hasFieldOrPropertyWithValue("httpStatusCode", 418);
            }
        }

        @ParameterizedTest
        @CsvSource({"py,single", "js,single", "py,multi", "js,multi"})
        @DisplayName(
                "Should raise generic ExtensionExecutionError if an extension raises a generic error during authorization")
        void shouldRaiseGenericErrorOnAuthorizationError(String lang, String cardinality) {
            List<ExtensionConfig> exts = new ArrayList<>();
            exts.add(getTestAuthExtension(TestExtensionType.PY_SINGLE, null, null, null, Map.of()));
            exts.add(getTestAuthExtension(TestExtensionType.JS, null, null, null, Map.of()));
            if (cardinality.equals("single")) {
                exts.remove(lang.equals("js") ? 0 : 1);
            }
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                String ident = "%s-raise".formatted(lang);
                Assertions.assertThatThrownBy(() -> runtime.authorize(ident, Map.of(), "127.0.0.1"))
                        .isInstanceOf(ExtensionExecutionException.class)
                        .hasMessageContaining("Extension raised an error during execution");
            }
        }
    }

    @Nested
    @DisplayName("Resolver Tests")
    class ResolverTests {

        @Test
        @DisplayName("Should return null when no resolver extensions are installed")
        void shouldReturnNullWithNoResolverExtensions() {
            try (ExtensionRuntime runtime = getRuntimeWithExtensions()) {
                assertThat(runtime.resolve("not-found", null, null)).isNull();
            }
        }

        @Test
        @DisplayName("Should resolve filesystem image")
        void shouldResolveFilesystemImage() {
            var exts = List.of(getTestResolverExtension("fs-", TestResolvingType.FILESYSTEM));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                var result = runtime.resolve("fs-test", null, null);
                assertThat(result).isNotNull();
                assertThat(result.resolvedImage()).isInstanceOf(FilesystemResolvedImage.class);
                var fsResolved = (FilesystemResolvedImage) result.resolvedImage();
                assertThat(fsResolved.path()).isEqualTo(Path.of("/tmp/images/test.jp2"));
            }
        }

        @Test
        @DisplayName("Should resolve HTTP image")
        void shouldResolveHttpImage() {
            var exts = List.of(getTestResolverExtension("http-", TestResolvingType.HTTP));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                var result = runtime.resolve("http-test", null, null);
                assertThat(result).isNotNull();
                assertThat(result.resolvedImage()).isInstanceOf(HttpResolvedImage.class);
                var httpResolved = (HttpResolvedImage) result.resolvedImage();
                assertThat(httpResolved.url()).isEqualTo(URI.create("https://example.com/resource/test"));
            }
        }

        @Test
        @DisplayName("Should resolve binary image")
        void shouldResolveBinaryImage() {
            var exts = List.of(getTestResolverExtension("", TestResolvingType.BINARY));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                var result = runtime.resolve("binary-test", null, null);
                assertThat(result).isNotNull();
                assertThat(result.resolvedImage()).isInstanceOf(BinaryResolvedImage.class);
                var binResolved = (BinaryResolvedImage) result.resolvedImage();
                assertThat(binResolved.rawData()).isNotNull().isNotEmpty();
                var img = VImage.newFromBytes(testArena, binResolved.rawData());
                assertThat(img.getWidth()).isEqualTo(1);
                assertThat(img.getHeight()).isEqualTo(1);
                assertThat(img.getpoint(0, 0)).containsExactly(190.0);
            }
        }

        @Test
        @DisplayName("Should resolve custom source image")
        void shouldResolveCustomSourceImage() {
            var exts = List.of(getTestResolverExtension("", TestResolvingType.CUSTOM));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                var result = runtime.resolve("custom-source-test", null, null);
                assertThat(result).isNotNull();
                assertThat(result.resolvedImage()).isInstanceOf(CustomSourceResolvedImage.class);
                var customResolved = (CustomSourceResolvedImage) result.resolvedImage();
                var img = VImage.newFromSource(
                        testArena, customResolved.sourceSupplier().apply(testArena));
                assertThat(img.getWidth()).isEqualTo(1);
                assertThat(img.getHeight()).isEqualTo(1);
                assertThat(img.getpoint(0, 0)).containsExactly(190.0);
            }
        }

        @Test
        @DisplayName("should handle notModified responses from the hook")
        void shouldHandleNotModifiedResponsesFromTheHook() {
            var exts = List.of(getTestResolverExtension("", TestResolvingType.FILESYSTEM));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                var resolved = runtime.resolve("not-modified-test", "not-modified", null);
                assertThat(resolved.resolvedImage()).isInstanceOf(SourceNotModified.class);
            }
        }

        @Test
        @DisplayName("Should resolve extended resolving results with cacheInfo")
        void shouldResolveWithCacheInfo() {
            var exts = List.of(getTestResolverExtension("", TestResolvingType.FILESYSTEM));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                var withCacheInfo = runtime.resolve("foo.withCacheInfo", null, null);
                assertThat(withCacheInfo).isNotNull();
                assertThat(withCacheInfo.resolvedImage()).isNotNull();
                assertThat(withCacheInfo.cacheInfo().eTag()).isEqualTo("js-extension-etag");
                assertThat(withCacheInfo.cacheInfo().lastModified()).isEqualTo(Instant.ofEpochSecond(1672531200));
            }
        }

        @Test
        @DisplayName("Should resolve extended resolving results with imageInfo")
        void shouldResolveWithImageInfo() {
            var exts = List.of(getTestResolverExtension("", TestResolvingType.FILESYSTEM));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                var withImageInfo = runtime.resolve("foo.withImageInfo", null, null);
                assertThat(withImageInfo).isNotNull();
                var imageInfo = withImageInfo.imageInfo();
                assertThat(imageInfo).isNotNull();
                assertThat(imageInfo.nativeSize()).isEqualTo(new ImageSize(1, 1));
                assertThat(imageInfo.tileSizes()).isEmpty();
                assertThat(imageInfo.sizes()).isEmpty();
            }
        }

        @Test
        @DisplayName("Should use first resolver that returns non-null result and cancel all others")
        void shouldUseFirstSuccessfulResolver() {
            var exts = List.of(
                    getTestResolverExtension("", TestResolvingType.BINARY),
                    getTestResolverExtension("fs-", TestResolvingType.FILESYSTEM),
                    getTestResolverExtension("should-take-a-long-time-over-http", TestResolvingType.HTTP));
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                long start = System.nanoTime();
                var result = runtime.resolve("data-anything", null, null);
                Duration duration = Duration.ofNanos(System.nanoTime() - start);
                assertThat(result).isNotNull();
                assertThat(result.resolvedImage()).isNotNull();
                assertThat(result.resolvedImage()).isInstanceOf(BinaryResolvedImage.class);
                // HTTP resolver sleeps for 10 seconds, so if it was not cancelled, the total duration would
                // be > 1 minute
                assertThat(duration).isLessThan(Duration.ofMillis(500));
            }
        }

        @ParameterizedTest
        @CsvSource({"js,single", "py,single", "js,multi", "py,single"})
        @DisplayName("Should raise an HttpStatusError if no extension could resolve and at least one raised an error")
        void shouldRaiseHttpStatusErrorOnAuthorizationError(String lang, String cardinality) {
            List<ExtensionConfig> exts = new ArrayList<>();
            exts.add(getTestResolverExtension("js-", TestResolvingType.FILESYSTEM));
            exts.add(getTestResolverExtension("py-", TestResolvingType.HTTP));
            if (cardinality.equals("single")) {
                exts.remove(lang.equals("js") ? 1 : 0);
            }
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                String ident = "%s-raise-http-418".formatted(lang);
                Assertions.assertThatThrownBy(() -> runtime.resolve(ident, null, null))
                        .isInstanceOf(HttpStatusException.class)
                        .hasFieldOrPropertyWithValue("httpStatusCode", 418);
            }
        }

        @ParameterizedTest
        @CsvSource({"py,single", "js,single", "py,multi", "js,multi"})
        @DisplayName(
                "Should raise generic ExtensionExecutionError if an extension raises a generic error during resolving")
        void shouldRaiseGenericErrorOnResolvingError(String lang, String cardinality) {
            List<ExtensionConfig> exts = new ArrayList<>();
            exts.add(getTestResolverExtension("py-", TestResolvingType.HTTP));
            exts.add(getTestResolverExtension("js-", TestResolvingType.FILESYSTEM));
            if (cardinality.equals("single")) {
                exts.remove(lang.equals("js") ? 0 : 1);
            }
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                String ident = "%s-raise".formatted(lang);
                Assertions.assertThatThrownBy(() -> runtime.resolve(ident, null, null))
                        .isInstanceOf(ExtensionExecutionException.class)
                        .hasMessageContaining("Extension raised an error during execution");
            }
        }
    }

    @Nested
    @DisplayName("Context Management Tests")
    class ContextManagementTests {
        private static final List<ExtensionConfig> exts = List.of(
                new ExtensionConfig(Path.of("src/test/resources/py-extension/single.py"), null, null, Map.of(), false),
                new ExtensionConfig(Path.of("src/test/resources/js-extension/index.js"), null, null, Map.of(), false));

        @Test
        @DisplayName("Should borrow contexts from pool for extensions")
        void shouldBorrowContextsFromPool() {
            assertThat(contextPool.getNumActive()).isZero();
            assertThat(contextPool.getNumIdle()).isZero();
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                // We expect one context per extension to be active in this block
                runtime.resolve("foo", null, null);
                assertThat(contextPool.getNumActive()).isEqualTo(2);
                runtime.resolve("fs-bar", null, null);
                assertThat(contextPool.getNumActive()).isEqualTo(2);
            }
            // After closing the runtime, all contexts should be returned to the pool, but not closed
            // (maxIdlePerKey is 2, so both contexts should be kept)
            assertThat(contextPool.getNumActive()).isZero();
            assertThat(contextPool.getNumIdle()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should reuse borrowed contexts for same extension")
        void shouldReuseBorrowedContexts() {
            assertThat(contextPool.getNumActive()).isZero();
            assertThat(contextPool.getNumIdle()).isZero();
            try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
                runtime.authorize("foo", Map.of(), "127.0.0.1");
                // First time we run extension code, we should have one active context per extension
                assertThat(contextPool.getNumActive()).isEqualTo(2);
                assertThat(contextPool.getNumIdle()).isZero();
                // The same extensions also implement the resolving hook, so we should still only have
                // two active contexts after we run that hook.
                runtime.resolve("foo", null, null);
                assertThat(contextPool.getNumActive()).isEqualTo(2);
                assertThat(contextPool.getNumIdle()).isZero();
            }
        }

        @Disabled
        @Test
        @DisplayName("Should run setup, cleanup and destroy hooks appropriately")
        void shouldRunSetupCleanupAndDestroyHooks() {
            List<Thread> threads = new ArrayList<>();
            List<String> threadNames = new ArrayList<>();
            Path pyHookLog = tempDir.resolve("py-hooks.log");
            Path jsHookLog = tempDir.resolve("js-hooks.log");
            List<ExtensionConfig> exts = List.of(
                    new ExtensionConfig(
                            Path.of("src/test/resources/py-extension/single.py"),
                            null,
                            null,
                            Map.of("logHooks", pyHookLog.toAbsolutePath().toString()),
                            false),
                    new ExtensionConfig(
                            Path.of("src/test/resources/js-extension/index.js"),
                            null,
                            null,
                            Map.of("logHooks", jsHookLog.toAbsolutePath().toString()),
                            false));
            config.extensions().addAll(exts);
            var registry = new ExtensionRegistry(
                    config,
                    pyPiInstaller,
                    npmInstaller,
                    null,
                    graalContextSupplier,
                    new GuestContextFactory(
                            buildProperties, httpClient, testArena, new ImageRequestParser(config), meterRegistry));
            var metrics = new WolpiMetrics(meterRegistry);
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                Thread t = new Thread(() -> {
                    try (ExtensionRuntime runtime =
                            new ExtensionRuntime.ExtensionRuntimeImpl(registry, contextPool, threadPool, metrics)) {
                        runtime.resolve("foo-%d".formatted(idx), null, null);
                    }
                });
                t.setName(UUID.randomUUID().toString());
                threads.add(t);
                threadNames.add(t.getName());
            }
            assertThat(contextPool.getNumActive()).isZero();
            assertThat(contextPool.getNumIdle()).isZero();
            // Start all threads
            threads.forEach(Thread::start);
            // Wait for all threads to finish
            threads.forEach(t -> {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // The actual order of hook execution is non-deterministic due to multithreading, but we can
            // check that:
            // - Each thread ran its own sequence of hooks
            // - Each thread's hooks are in the correct order: info, setup, resolve, cleanup
            // - At least one destroy hook was called (we have 2 max idle contexts, so at least one
            //   context should have been destroyed)
            Predicate<? super List<? extends String>> hookSequencePredicate = (List<? extends String> seq) -> {
                // for each setup hook after the first one (for info), there should be
                // a resolve and cleanup hook after it.
                int setupCount = 0;
                int resolveCount = 0;
                int cleanupCount = 0;
                for (String hook : seq.subList(2, seq.size())) {
                    switch (hook) {
                        case "setup" -> setupCount++;
                        case "resolve" -> resolveCount++;
                        case "cleanup" -> cleanupCount++;
                    }
                }
                return setupCount == 3 && resolveCount == 3 && cleanupCount == 3;
            };
            assertThat(readHookLog(pyHookLog).stream().map(HookLogEntry::hookName))
                    .startsWith("info", "destroy")
                    .endsWith("destroy")
                    .matches(hookSequencePredicate, "has correct hook sequence");
            assertThat(readHookLog(jsHookLog).stream().map(HookLogEntry::hookName))
                    .startsWith("info", "destroy")
                    .endsWith("destroy")
                    .matches(hookSequencePredicate, "has correct hook sequence");
        }
    }

    private record HookLogEntry(Instant timestamp, String hookName, String threadName) {}

    private List<HookLogEntry> readHookLog(Path hookLogPath) {
        try {
            if (!Files.exists(hookLogPath)) {
                return List.of();
            }
            return Files.readAllLines(hookLogPath).stream()
                    .map(line -> {
                        var parts = line.split(" ");
                        if (!parts[0].endsWith("Z")) {
                            parts[0] = parts[0] + "Z";
                        }
                        return new HookLogEntry(
                                Instant.parse(parts[0]), parts[2], parts[1].substring(1, parts[1].length() - 1));
                    })
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Should augment info.json if extensions implement hook")
    void shouldAugmentInfoJson() {
        var exts = List.of(
                new ExtensionConfig(Path.of("src/test/resources/py-extension/single.py"), null, null, Map.of(), false),
                new ExtensionConfig(Path.of("src/test/resources/js-extension/index.js"), null, null, Map.of(), false));
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
            var info = runtime.augmentInfoJson("someIdentifier", Map.of("id", "python-id"), IIIFVersion.V3);
            assertThat(info).isNotNull();
            assertThat(info.get("augmentedFromPython")).isEqualTo("someIdentifier-3");
            assertThat(info.get("augmentedFromJS")).isEqualTo("someIdentifier-3");
            assertThat(info.get("id")).isEqualTo("python-id");
        }
    }

    @Test
    @DisplayName("Should execute preprocess hooks on image")
    void shouldExecutePreprocessHooks() {
        var exts = List.of(
                new ExtensionConfig(
                        Path.of("src/test/resources/py-extension/single.py"),
                        null,
                        null,
                        Map.of("watermarkColor", "#FF0000"),
                        false),
                new ExtensionConfig(
                        Path.of("src/test/resources/js-extension/index.js"),
                        null,
                        null,
                        Map.of("watermarkColor", "#0000FF"),
                        false));
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
            VImage img = VImageHelpers.createEmptyImage(testArena, 500, 500, Color.green);
            VImage processed = runtime.preProcessImage(
                    img,
                    "watermarked:some-id",
                    new ImageInfo(new ImageSize(500, 500), List.of(), List.of()),
                    ImageRequest.full("watermarked:some-id", IIIFVersion.V3));

            assertThat(processed)
                    .hasDimensions(500, 500)
                    .regionEquals(0, 0, VImageHelpers.createEmptyImage(testArena, 100, 100, Color.red))
                    .regionEquals(0, 100, VImageHelpers.createEmptyImage(testArena, 100, 100, Color.blue));
        }
    }

    @ParameterizedTest
    @CsvSource({"xyz,eHl6", "pixl,UElYTERBVEE="})
    @DisplayName("Should execute format hooks on image")
    void shouldExecuteFormatHooks(String format, String expectedDataBase64) {
        ByteBuffer expectedData = ByteBuffer.wrap(Base64.getDecoder().decode(expectedDataBase64));
        try (var runtime = getRuntimeWithSingleFileExtensions()) {
            VImage img = VImageHelpers.createEmptyImage(testArena, 10, 10, Color.yellow);
            EncodedImage encoded = runtime.preFormat(
                    img,
                    "image-" + format,
                    new ImageInfo(new ImageSize(10, 10), List.of(), List.of()),
                    new ImageRequest("image-" + format, IIIFVersion.V3, "full", "full", "0", "default", format));
            assertThat(encoded).isNotNull();
            assertThat(encoded.data()).isEqualByComparingTo(expectedData);
            var headers = encoded.extraHeaders();
            assertThat(headers).isNotNull();
            var sourceEntry = headers.get("X-Wolpi-Encoding-Source");
            assertThat(sourceEntry).isNotNull().hasSize(1);
            assertThat(sourceEntry).containsExactly("image-" + format);
        }
    }

    private ExtensionRuntime getRuntimeWithSingleFileExtensions() {
        var exts = List.of(
                new ExtensionConfig(Path.of("src/test/resources/py-extension/single.py"), null, null, Map.of(), false),
                new ExtensionConfig(Path.of("src/test/resources/js-extension/index.js"), null, null, Map.of(), false));
        return getRuntimeWithExtensions(exts);
    }

    @Test
    @DisplayName("Should treat implemented hooks as non-skippable by default")
    void shouldTreatImplementedHooksAsNonSkippableByDefault() throws IOException {
        var ext = writeJsExtension("no-skippable-hooks", """
                export function info() {
                  return { name: "No Skippable Hooks", apiVersion: 1, description: "test" };
                }

                export function cleanup() {}

                export function preScale() {
                  return null;
                }
                """);
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(ext)) {
            var skippableHooks = runtime.getSkippableHooks(ImageRequest.full("some-image", IIIFVersion.V3));
            assertThat(skippableHooks).doesNotContain(ExtensionHooks.SCALE);
            assertThat(skippableHooks)
                    .contains(
                            ExtensionHooks.PREPROCESS_IMAGE,
                            ExtensionHooks.CROP,
                            ExtensionHooks.ROTATE,
                            ExtensionHooks.QUALITY,
                            ExtensionHooks.FORMAT);
        }
    }

    @Test
    @DisplayName("Should intersect default skippable hooks across extensions")
    void shouldIntersectDefaultGetSkippableHooksAcrossExtensions() throws IOException {
        var scaleExt = writeJsExtension("default-skippable-scale", """
                export function info() {
                  return { name: "Default Scale", apiVersion: 1, description: "test" };
                }

                export function cleanup() {}

                export function preScale() {
                  return null;
                }
                """);
        var cropExt = writeJsExtension("default-skippable-crop", """
                export function info() {
                  return { name: "Default Crop", apiVersion: 1, description: "test" };
                }

                export function cleanup() {}

                export function preCrop() {
                  return null;
                }
                """);
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(List.of(scaleExt, cropExt))) {
            var skippableHooks = runtime.getSkippableHooks(ImageRequest.full("some-image", IIIFVersion.V3));
            assertThat(skippableHooks).doesNotContain(ExtensionHooks.SCALE, ExtensionHooks.CROP);
            assertThat(skippableHooks)
                    .contains(
                            ExtensionHooks.PREPROCESS_IMAGE,
                            ExtensionHooks.ROTATE,
                            ExtensionHooks.QUALITY,
                            ExtensionHooks.FORMAT);
        }
    }

    @Test
    @DisplayName("Should allow JS extensions to explicitly mark implemented hooks as skippable")
    void shouldAllowJsExtensionsToExplicitlyMarkImplementedHooksAsSkippable() throws IOException {
        var ext = writeJsExtension("explicit-skippable-hooks", """
                export function info() {
                  return { name: "Explicit Skippable", apiVersion: 1, description: "test" };
                }

                export function cleanup() {}

                export function preScale() {
                  return null;
                }

                export function skippableHooks(request) {
                  return ["SCALE"];
                }
                """);
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(ext)) {
            var skippableHooks = runtime.getSkippableHooks(ImageRequest.full("some-image", IIIFVersion.V3));
            assertThat(skippableHooks).contains(ExtensionHooks.SCALE);
        }
    }

    @ParameterizedTest
    @CsvSource({"SCALE", "pre_scale", "preScale", "scale"})
    @DisplayName("Should allow Python extensions to explicitly mark implemented hooks as skippable")
    void shouldAllowPythonExtensionsToExplicitlyMarkImplementedHooksAsSkippable(String hookName) throws IOException {
        var ext = writePyExtension("explicit-skippable-hooks-%s".formatted(hookName), """
                def info():
                    return {"name": "Explicit Python Skippable", "apiVersion": 1, "description": "test"}

                def cleanup():
                    pass

                def pre_scale(image, identifier, image_info, iiif_request):
                    return None

                def skippable_hooks(iiif_request):
                    return {"%s"}
                """.formatted(hookName));
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(ext)) {
            var skippableHooks = runtime.getSkippableHooks(ImageRequest.full("some-image", IIIFVersion.V3));
            assertThat(skippableHooks).contains(ExtensionHooks.SCALE);
        }
    }

    @Test
    @DisplayName("Should pass image requests to Python skippable hooks as field-accessible records")
    void shouldPassImageRequestToPythonSkippableHooksAsFieldAccessibleRecord() throws IOException {
        var ext = writePyExtension("field-accessible-skippable-request", """
                def info():
                    return {"name": "Field Accessible Skippable Request", "apiVersion": 1, "description": "test"}

                def cleanup():
                    pass

                def pre_quality(image, identifier, image_info, iiif_request):
                    return None

                def skippable_hooks(iiif_request):
                    if iiif_request.qualitySpec == "filter:glow-up":
                        return set()
                    return {"pre_quality"}
                """);
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(ext)) {
            var customQualityRequest =
                    new ImageRequest("some-image", IIIFVersion.V3, "full", "max", "0", "filter:glow-up", "jpg");
            var skippableHooks = runtime.getSkippableHooks(customQualityRequest);

            assertThat(skippableHooks).doesNotContain(ExtensionHooks.QUALITY);
        }
    }

    @Test
    @DisplayName("Should treat inherited WolpiExtension default hooks as skippable by default")
    void shouldTreatInheritedWolpiExtensionDefaultHooksAsSkippableByDefault() throws IOException {
        var ext = writePyExtension("wolpi-extension-default-hooks", """
                from wolpi import WolpiExtension


                class Extension(WolpiExtension):
                    def info(self):
                        return {"name": "Default Hook Inheritance", "apiVersion": 1, "description": "test"}

                    def cleanup(self):
                        pass


                def wolpi_extension():
                    return Extension()
                """);
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(ext)) {
            var skippableHooks = runtime.getSkippableHooks(ImageRequest.full("some-image", IIIFVersion.V3));
            assertThat(skippableHooks)
                    .contains(
                            ExtensionHooks.PREPROCESS_IMAGE,
                            ExtensionHooks.CROP,
                            ExtensionHooks.SCALE,
                            ExtensionHooks.ROTATE,
                            ExtensionHooks.QUALITY,
                            ExtensionHooks.FORMAT);
        }
    }

    @Test
    @DisplayName("Should expose WolpiExtension helper on injected Python wolpi module")
    void shouldExposeWolpiExtensionHelperInPython() throws IOException {
        var ext = writePyExtension("wolpi-extension-helper", """
                from wolpi import WolpiExtension


                class Extension(WolpiExtension):
                    def info(self):
                        return {"name": "WolpiExtension Helper", "apiVersion": 1, "description": "test"}

                    def cleanup(self):
                        pass

                    def authorize(self, identifier, headers, client_ip):
                        return identifier == "allowed"


                ext = Extension()


                def info():
                    return ext.info()


                def cleanup():
                    return ext.cleanup()


                def authorize(identifier, headers, client_ip):
                    return ext.authorize(identifier, headers, client_ip)
                """);
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(ext)) {
            assertThat(runtime.authorize("allowed", Map.of(), "127.0.0.1")).isTrue();
            assertThat(runtime.authorize("denied", Map.of(), "127.0.0.1")).isFalse();
        }
    }

    @Test
    @DisplayName("Should allow unified imports of Wolpi runtime values and typing helpers in Python")
    void shouldAllowUnifiedImportsFromPythonWolpiModule() throws IOException {
        var ext = writePyExtension("wolpi-unified-imports", """
                from wolpi import ExtensionInfo, ImageApiRequest, ImageInfo, VImage, WolpiExtension, vipsArena


                class Extension(WolpiExtension):
                    def info(self) -> ExtensionInfo:
                        assert vipsArena is not None
                        return {"name": "Unified Imports", "apiVersion": 1, "description": "test"}

                    def cleanup(self) -> None:
                        pass

                    def pre_scale(
                        self,
                        image: VImage,
                        identifier: str,
                        image_info: ImageInfo,
                        request: ImageApiRequest,
                    ) -> VImage | None:
                        assert request.sizeSpec is not None
                        assert image_info.nativeSize.width > 0
                        return image


                ext = Extension()


                def info():
                    return ext.info()


                def cleanup():
                    return ext.cleanup()


                def pre_scale(image, identifier, image_info, request):
                    return ext.pre_scale(image, identifier, image_info, request)
                """);
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(ext)) {
            VImage img = VImageHelpers.createEmptyImage(testArena, 500, 500, Color.green);
            VImage preScaled = runtime.preScale(
                    img,
                    "some-image",
                    new ImageInfo(new ImageSize(500, 500), List.of(), List.of()),
                    new ImageRequest("some-image", IIIFVersion.V3, null, "max", null, null, null));
            assertThat(preScaled).isSameAs(img);
        }
    }

    @Test
    @DisplayName("Should execute pre-scale hooks on image")
    void shouldExecutePreScaleHooks() {
        var exts = List.of(
                new ExtensionConfig(Path.of("src/test/resources/py-extension/single.py"), null, null, Map.of(), false),
                // should never be called
                new ExtensionConfig(Path.of("src/test/resources/js-extension/index.js"), null, null, Map.of(), false));
        var featuresMock = mock(IIIFConfig.Features.class);
        var iiifMock = config.iiif();
        lenient().when(iiifMock.features()).thenReturn(featuresMock);
        lenient().when(iiifMock.limits()).thenReturn(new Limits(0, 0, 0));
        lenient().when(featuresMock.scaling()).thenReturn(new ScalingFeature(true, true, true, true, true, true));
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
            VImage img = VImageHelpers.createEmptyImage(testArena, 500, 500, Color.green);
            VImage preScaled = runtime.preScale(
                    img,
                    "some-image",
                    new ImageInfo(new ImageSize(500, 500), List.of(), List.of()),
                    new ImageRequest("some-image", IIIFVersion.V3, null, "custom:20,100", null, null, null));
            assertThat(preScaled).hasDimensions(20, 100);
        }
    }

    @Test
    @DisplayName("Should execute pre-crop hooks on image")
    void shouldExecutePreCropHooks() {
        var exts = List.of(
                new ExtensionConfig(Path.of("src/test/resources/py-extension/single.py"), null, null, Map.of(), false));
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
            VImage img = VImageHelpers.createEmptyImage(testArena, 500, 500, Color.green);
            img.drawRect(List.of(255.0, 0.0, 0.0), 50, 60, 100, 200, VipsOption.Boolean("fill", true));
            VImage preCropped = runtime.preCrop(
                    img,
                    "some-image",
                    new ImageInfo(new ImageSize(500, 500), List.of(), List.of()),
                    new ImageRequest("some-image", IIIFVersion.V3, "custom:50,60,100,200", null, null, null, null));
            assertThat(preCropped).hasDimensions(100, 200);
            assertThat(preCropped).equals(VImageHelpers.createEmptyImage(testArena, 100, 200, Color.red));
        }
    }

    @Test
    @DisplayName("Should execute pre-rotate hooks on image")
    void shouldExecutePreRotateHooks() {
        var exts = List.of(
                new ExtensionConfig(Path.of("src/test/resources/py-extension/single.py"), null, null, Map.of(), false));
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
            VImage img = VImageHelpers.createEmptyImage(testArena, 500, 500, Color.green);
            img.drawRect(List.of(255.0, 0.0, 0.0), 0, 0, 300, 100, VipsOption.Boolean("fill", true));
            img.set("exif-ifd0-Orientation", 8);
            VImage preRotated = runtime.preRotate(
                    img,
                    "some-image",
                    new ImageInfo(new ImageSize(500, 500), List.of(), List.of()),
                    new ImageRequest("some-image", IIIFVersion.V3, null, null, "custom:metadata", null, null));
            assertThat(preRotated).equals(img.rotate(90.0));
        }
    }

    @Test
    @DisplayName("Should allow extensions to register and update custom metrics")
    void shouldAllowExtensionsToRegisterAndUpdateCustomMetrics() {
        try (var runtime = getRuntimeWithExtensions(
                List.of(new ExtensionConfig(Path.of("src/test/resources/metrics.js"), null, null, Map.of(), false)))) {
            var counter = meterRegistry.get("metrics_test_counter").counter();
            assertThat(counter).isNotNull();
            var gauge = meterRegistry.get("metrics_test_gauge").gauge();
            assertThat(gauge).isNotNull();
            var timer = meterRegistry.get("metrics_test_timer").timer();
            assertThat(timer).isNotNull();

            runtime.authorize("foo", Map.of(), "");
            Assertions.assertThat(counter.count()).isEqualTo(1.0);
            runtime.authorize("increment-four", Map.of(), "");
            Assertions.assertThat(counter.count()).isEqualTo(5.0);
            Assertions.assertThat(gauge.value()).isEqualTo(0.0);
            runtime.authorize("gauge-4", Map.of(), "");
            Assertions.assertThat(gauge.value()).isEqualTo(4.0);

            Assertions.assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(0.0);
            runtime.authorize("timed-4000", Map.of(), "");
            Assertions.assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(4000.0);
            runtime.resolve("timed-resolve", null, null);
            Assertions.assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(7000.0);
        }
    }

    @Test
    @DisplayName("Should support default-exported JavaScript class instances")
    void shouldSupportJsClassInstances() {
        var exts = List.of(
                new ExtensionConfig(Path.of("src/test/resources/js-class-instance.js"), null, null, Map.of(), false));
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
            assertThat(runtime.authorize("class-instance-authorize", Map.of(), "127.0.0.1"))
                    .isTrue();
            var resolved = runtime.resolve("class-instance-resolve", null, null);
            assertThat(resolved).isNotNull();
            assertThat(resolved.resolvedImage()).isInstanceOf(FilesystemResolvedImage.class);
            var fsResolved = (FilesystemResolvedImage) resolved.resolvedImage();
            assertThat(fsResolved.path()).isEqualTo(Path.of("/tmp/images/class-instance.jp2"));
        }
    }

    @Test
    @DisplayName("Should execute pre-color hooks on image")
    void shouldExecutePreQualityHooks() {
        var exts = List.of(
                new ExtensionConfig(Path.of("src/test/resources/py-extension/single.py"), null, null, Map.of(), false));
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(exts)) {
            VImage img = VImageHelpers.createEmptyImage(testArena, 500, 500, Color.black);
            VImage invertedImage = runtime.preQuality(
                    img,
                    "some-image",
                    new ImageInfo(new ImageSize(500, 500), List.of(), List.of()),
                    new ImageRequest("some-image", IIIFVersion.V3, null, null, null, "custom:invert", null));
            assertThat(invertedImage).equals(VImageHelpers.createEmptyImage(testArena, 500, 500, Color.white));
        }
    }

    @ParameterizedTest
    @CsvSource({"400,js", "400,py", "418,js", "418,py"})
    @DisplayName("Should raise HTTP status code when error with status code is raised in extension")
    void shouldRaiseHttpStatusCodeFromExtension(int statusCode, String extType) {
        ExtensionConfig extConfig =
                switch (extType) {
                    case "js" ->
                        new ExtensionConfig(
                                Path.of("src/test/resources/js-extension/index.js"), null, null, null, false);
                    case "py" ->
                        new ExtensionConfig(
                                Path.of("src/test/resources/py-extension/single.py"), null, null, null, false);
                    default -> throw new IllegalArgumentException("Unknown extension type: " + extType);
                };
        try (ExtensionRuntime runtime = getRuntimeWithExtensions(extConfig)) {
            Assertions.assertThatThrownBy(() ->
                            runtime.authorize("%s-raise-http-%d".formatted(extType, statusCode), Map.of(), "127.0.0.1"))
                    .isInstanceOf(HttpStatusException.class)
                    .hasMessageContaining("HTTP %d from %s".formatted(statusCode, extType))
                    .hasFieldOrPropertyWithValue("httpStatusCode", statusCode);
        }
    }

    private ExtensionRuntime getRuntimeWithExtensions(ExtensionConfig... extensions) {
        return getRuntimeWithExtensions(List.of(extensions));
    }

    private ExtensionRuntime getRuntimeWithExtensions(List<ExtensionConfig> extensions) {
        config.extensions().addAll(extensions);
        var registry = new ExtensionRegistry(
                config,
                pyPiInstaller,
                npmInstaller,
                null,
                graalContextSupplier,
                new GuestContextFactory(
                        buildProperties, httpClient, testArena, new ImageRequestParser(config), meterRegistry));
        var metrics = new WolpiMetrics(meterRegistry);
        return new ExtensionRuntime.ExtensionRuntimeImpl(registry, contextPool, threadPool, metrics) {
            @Override
            public void close() {
                super.close();
                try {
                    // Each helper call creates a dedicated registry with its own file monitor, so
                    // tests must close it alongside the runtime to avoid leaking watcher threads.
                    registry.close();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to close test extension registry", e);
                }
            }
        };
    }

    private ExtensionConfig writeJsExtension(String name, String source) throws IOException {
        Path path = tempDir.resolve("%s-%s.js".formatted(name, UUID.randomUUID()));
        Files.writeString(path, source);
        return new ExtensionConfig(path, null, null, Map.of(), false);
    }

    private ExtensionConfig writePyExtension(String name, String source) throws IOException {
        Path path = tempDir.resolve("%s-%s.py".formatted(name, UUID.randomUUID()));
        Files.writeString(path, source);
        return new ExtensionConfig(path, null, null, Map.of(), false);
    }

    private ExtensionConfig getTestAuthExtension(
            TestExtensionType extType,
            @Nullable List<String> allowedIds,
            @Nullable List<String> forbiddenIds,
            @Nullable List<String> allowedIPs,
            @Nullable Map<String, String> requiredHeaders) {
        Map<String, Object> cfg = new HashMap<>();
        if (allowedIds != null) {
            cfg.put("allowedIds", allowedIds);
        }
        if (forbiddenIds != null) {
            cfg.put("forbiddenIds", forbiddenIds);
        }
        if (allowedIPs != null) {
            cfg.put("allowedIps", allowedIPs);
        }
        if (requiredHeaders != null) {
            cfg.put("requiredHeaders", requiredHeaders);
        }
        return new ExtensionConfig(
                Path.of(
                        switch (extType) {
                            case PY_SINGLE -> "src/test/resources/py-extension/single.py";
                            case PY_PKG -> "src/test/resources/py-extension";
                            case JS -> "src/test/resources/js-extension/index.js";
                        }),
                null,
                null,
                cfg,
                false);
    }

    private ExtensionConfig getTestResolverExtension(String prefix, TestResolvingType resolvingType) {
        Path path = Path.of(
                switch (resolvingType) {
                    case FILESYSTEM, BINARY, CUSTOM -> "src/test/resources/js-extension/index.js";
                    case HTTP -> "src/test/resources/py-extension/single.py";
                });
        return new ExtensionConfig(
                path, null, null, Map.of("prefix", prefix, "resolvingType", resolvingType.name()), false);
    }

    private enum TestResolvingType {
        FILESYSTEM,
        HTTP,
        BINARY,
        CUSTOM
    }

    private enum TestExtensionType {
        PY_SINGLE,
        PY_PKG,
        JS
    }
}
