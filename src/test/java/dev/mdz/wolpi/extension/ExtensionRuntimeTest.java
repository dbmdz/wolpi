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
import dev.mdz.wolpi.config.WolpiConfig.PackagingConfig;
import dev.mdz.wolpi.extension.PyPiInstaller.EntryPoint;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.iiif.ImageRequestParser;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.iiif.model.ImageRequest;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.info.BuildProperties;

@DisplayName("ExtensionRuntime")
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

    private KeyedObjectPool<LoadedExtension, RuntimeContext> contextPool = new GenericKeyedObjectPool<>(
            new RuntimeContextPooledObjectFactory(new GraalContextSupplier(null)),
            new GenericKeyedObjectPoolConfig<>() {
                {
                    setMaxIdlePerKey(2);
                    setMaxTotalPerKey(4);
                    setJmxEnabled(false);
                }
            });

    private ThreadPoolExecutor threadPool =
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
        Path source = Path.of("src/test/resources/py-extension");
        Files.walk(source).filter(s -> s != source).forEach(s -> {
            try {
                Files.copy(s, libPath.resolve(source.relativize(s)));
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
                mock(ExtensionPoolConfig.class),
                new ExtensionDebugConfig(false, "localhost", 4711, false, false),
                mock(PackagingConfig.class),
                Map.of());

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
                new GraalContextSupplier(config),
                new GuestContextFactory(
                        buildProperties, httpClient, testArena, new ImageRequestParser(config), meterRegistry));
        return new ExtensionRuntime.ExtensionRuntimeImpl(registry, contextPool, threadPool);
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
