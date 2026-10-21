package dev.mdz.wolpi.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import com.sun.net.httpserver.HttpServer;
import dev.mdz.wolpi.config.HttpSourceCacheConfig;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.exceptions.HttpStatusException;
import dev.mdz.wolpi.extension.ExtensionRuntime;
import dev.mdz.wolpi.http.TimeoutHttpClient;
import dev.mdz.wolpi.http.cache.HttpSourceCache;
import dev.mdz.wolpi.metrics.WolpiMetrics;
import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.ImageSize;
import dev.mdz.wolpi.model.ImageSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

class ImageLoaderTest {
    private static final Path IMAGES = Path.of("src/test/resources/images");
    private static Arena arena;
    private static ImageLoader imageLoader;

    @BeforeAll
    static void setup() {
        Vips.init();
        arena = Arena.ofConfined();
        imageLoader = loader(IMAGES, null, mock(HttpSourceCache.class), arena);
    }

    @AfterAll
    static void teardown() {
        arena.close();
    }

    private static WolpiConfig config(Path images) {
        return new WolpiConfig(
                Path.of("/tmp/wolpi-test-tmp"),
                images,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                null);
    }

    private static ImageLoader loader(
            Path images, @Nullable ExtensionRuntime runtime, HttpSourceCache cache, Arena scope) {
        return new ImageLoader(config(images), scope, runtime, null, mock(WolpiMetrics.class), cache);
    }

    private static HttpSourceCache uncached(HttpClient client) {
        var defaults = HttpSourceCacheConfig.defaults();
        var disabled = new HttpSourceCacheConfig(
                false,
                null,
                defaults.maxSize(),
                defaults.maxFreshness(),
                defaults.maxResourceSize(),
                false,
                defaults.staleIfError());
        return new HttpSourceCache(
                disabled, Path.of("/tmp/wolpi-test-tmp"), client, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    @ParameterizedTest(name = "should map ''{0}'' to format ''{1}''")
    @CsvSource({"embedded_icc_colorspace.jpg, jpeg", "embedded_icc_colorspace.jp2, jp2k"})
    void testImageInfoFormatExtraction(String imageName, String expectedFormat) throws Exception {
        VImage image = VImage.newFromFile(arena, IMAGES.resolve(imageName).toString());
        var info = imageLoader.getImageInfo(new ImageSource(imageName, null, null, null), image);
        assertThat(info.format()).isEqualTo(expectedFormat);
    }

    @Test
    void shouldThrowHttpStatusExceptionOnHttpErrors() throws Exception {
        var loader = httpImageLoader(Map.of(), 404);
        assertThatThrownBy(() -> loader.loadImage(httpSource(null)))
                .isInstanceOf(HttpStatusException.class)
                .extracting("httpStatusCode")
                .isEqualTo(404);
    }

    @Test
    void unsolicitedSource304IsNotAClient304() throws Exception {
        var loader = httpImageLoader(Map.of("ETag", List.of("\"origin-etag\"")), 304);
        assertThatThrownBy(() -> loader.loadImage(httpSource(null)))
                .isInstanceOf(HttpStatusException.class)
                .extracting("httpStatusCode")
                .isEqualTo(502);
    }

    @Test
    void clientConditionsAreOnlyPassedToTheResolver() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(404);
        when(response.body()).thenReturn(mock(InputStream.class));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        ExtensionRuntime runtime = mock(ExtensionRuntime.class);
        when(runtime.resolve("remote-image", "client-etag", null)).thenReturn(httpSource(null));
        var loader = loader(IMAGES, runtime, uncached(client), arena);
        ImageSource source = loader.resolve("remote-image", "client-etag", null);
        assertThatThrownBy(() -> loader.loadImage(source)).isInstanceOf(HttpStatusException.class);
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captured.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(captured.getValue().headers().firstValue("If-None-Match")).isEmpty();
        verify(runtime).resolve("remote-image", "client-etag", null);
    }

    @Test
    void shouldResolveFilesystemIdentifierInsideBaseDirectory(@TempDir Path dir) throws IOException {
        Path base = dir.resolve("images");
        Files.createDirectories(base);
        Path image = base.resolve("image.jp2");
        Files.writeString(image, "placeholder");
        var source = filesystemFallbackLoader(base).resolve("image.jp2", null, null);
        assertThat(((FilesystemResolvedImage) source.resolvedImage()).path()).isEqualTo(image.toRealPath());
    }

    @Test
    void shouldRejectFilesystemTraversalOutsideBaseDirectory(@TempDir Path dir) throws IOException {
        Path base = dir.resolve("images");
        Files.createDirectories(base);
        Files.writeString(dir.resolve("secret.jp2"), "secret");
        assertThat(filesystemFallbackLoader(base).resolve("../secret.jp2", null, null))
                .isNull();
    }

    @Test
    void shouldRejectAbsoluteFilesystemPathOutsideBaseDirectory(@TempDir Path dir) throws IOException {
        Path base = dir.resolve("images");
        Files.createDirectories(base);
        Path secret = dir.resolve("secret.jp2");
        Files.writeString(secret, "secret");
        assertThat(filesystemFallbackLoader(base).resolve(secret.toString(), null, null))
                .isNull();
    }

    @Test
    void shouldRejectFilesystemSymlinkOutsideBaseDirectory(@TempDir Path dir) throws IOException {
        Path base = dir.resolve("images");
        Files.createDirectories(base);
        Path secret = dir.resolve("secret.jp2");
        Files.writeString(secret, "secret");
        try {
            Files.createSymbolicLink(base.resolve("linked.jp2"), secret);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symbolic links unavailable");
        }
        assertThat(filesystemFallbackLoader(base).resolve("linked.jp2", null, null))
                .isNull();
    }

    @Test
    void shouldCaptureUpstreamCacheHeadersIntoCacheInfo() throws Exception {
        var modified =
                ZonedDateTime.of(2026, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC).toInstant();
        var loader = httpImageLoader(
                Map.of(
                        "ETag",
                        List.of("\"s3-etag\""),
                        "Last-Modified",
                        List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(modified.atZone(ZoneOffset.UTC)))),
                200);
        var source = httpSource(null);
        loader.loadImage(source);
        assertThat(source.cacheInfo().eTag()).isEqualTo("\"s3-etag\"");
        assertThat(source.cacheInfo().lastModified()).isEqualTo(modified);
        assertThat(source.resolverValidators()).isNull();
    }

    @Test
    void shouldNotOverwriteCacheInfoWhenAlreadySet() throws Exception {
        var source = httpSource(new CacheInfo("extension-etag", null));
        httpImageLoader(Map.of("ETag", List.of("\"upstream-etag\"")), 200).loadImage(source);
        assertThat(source.cacheInfo().eTag()).isEqualTo("extension-etag");
    }

    @Test
    void shouldNotSetCacheInfoWhenUpstreamHasNoHeaders() throws Exception {
        var source = httpSource(null);
        httpImageLoader(Map.of(), 200).loadImage(source);
        assertThat(source.cacheInfo()).isNull();
    }

    @Test
    void source304WithoutBodyRetainsDiagnosticHeadersButReturns502() throws Exception {
        var loader = httpImageLoader(Map.of("ETag", List.of("\"s3-current-etag\"")), 304);
        assertThatThrownBy(() -> loader.loadImage(httpSource(null)))
                .isInstanceOf(HttpStatusException.class)
                .satisfies(error -> {
                    var status = (HttpStatusException) error;
                    assertThat(status.httpStatusCode()).isEqualTo(502);
                    assertThat(status.responseHeaders().getETag()).isEqualTo("\"s3-current-etag\"");
                });
    }

    private ImageLoader httpImageLoader(Map<String, List<String>> headers, int status) throws Exception {
        byte[] bytes = Files.readAllBytes(IMAGES.resolve("embedded_icc_colorspace.jpg"));
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(bytes));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return loader(IMAGES, null, uncached(client), arena);
    }

    @Test
    @SuppressWarnings("unchecked")
    void uncachedReplacementClearsValidatorsFromThePreviousResponse() throws Exception {
        byte[] bytes = Files.readAllBytes(IMAGES.resolve("embedded_icc_colorspace.jpg"));
        HttpClient client = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        var requests = new AtomicInteger();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(ignored -> {
                    var headers = requests.incrementAndGet() == 1
                            ? Map.of("ETag", List.of("\"v1\""))
                            : Map.<String, List<String>>of();
                    when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
                    when(response.body()).thenReturn(new ByteArrayInputStream(bytes));
                    return response;
                });
        try (var cache = uncached(client);
                var request = Arena.ofConfined()) {
            var loader = loader(IMAGES, null, cache, request);
            var source = httpSource(null);
            assertThat(loader.getImageInfo(source)).isNotNull();
            assertThat(source.cacheInfo().eTag()).isEqualTo("\"v1\"");
            loader.loadImage(source);
            assertThat(source.cacheInfo()).isNull();
            assertThat(source.resolverValidators()).isNull();
            assertThat(requests).hasValue(2);
        }
    }

    private static ImageSource httpSource(@Nullable CacheInfo version) {
        return new ImageSource(
                "remote-image",
                new HttpResolvedImage(URI.create("https://example.com/image.jpg"), null),
                null,
                version);
    }

    private ImageLoader filesystemFallbackLoader(Path base) {
        ExtensionRuntime runtime = mock(ExtensionRuntime.class);
        when(runtime.resolve(any(), any(), any())).thenReturn(null);
        return loader(base, runtime, mock(HttpSourceCache.class), arena);
    }

    @Test
    void infoAndTilesReuseTheWholeBodyAcrossSignedUrls(@TempDir Path dir) throws Exception {
        byte[] bytes = Files.readAllBytes(IMAGES.resolve("embedded_icc_colorspace.jpg"));
        AtomicInteger downloads = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image.jpg", exchange -> {
            downloads.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Range")).isNull();
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=600");
            exchange.getResponseHeaders().set("ETag", "\"image-v1\"");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/image.jpg");
        var settings = new HttpSourceCacheConfig(
                true,
                dir.resolve("cache"),
                DataSize.ofBytes(bytes.length * 2L),
                Duration.ofMinutes(1),
                DataSize.ofBytes(bytes.length),
                true,
                Duration.ofDays(7));
        var signatures = new AtomicInteger();
        ExtensionRuntime runtime = mock(ExtensionRuntime.class);
        when(runtime.resolve(any(), any(), any()))
                .thenAnswer(ignored -> new ImageSource(
                        "remote-image",
                        new HttpResolvedImage(
                                URI.create(uri + "?signature=" + signatures.incrementAndGet()),
                                null,
                                "resolver:tenant-a:object"),
                        null,
                        null));
        try (var client = new TimeoutHttpClient(config(dir).httpTimeouts().forSources());
                var cache = new HttpSourceCache(settings, dir, client, Clock.systemUTC(), Duration.ofSeconds(30))) {
            try (Arena request = Arena.ofConfined()) {
                var loader = loader(dir, runtime, cache, request);
                var source = loader.resolve("remote-image", null, null);
                assertThat(loader.getImageInfo(source)).isNotNull();
                assertThat(source.httpSourceCacheStatus()).isEqualTo("miss");
            }
            for (int i = 0; i < 3; i++) {
                try (Arena request = Arena.ofConfined()) {
                    var loader = loader(dir, runtime, cache, request);
                    var source = loader.resolve("remote-image", null, null);
                    assertThat(((HttpResolvedImage) source.resolvedImage())
                                    .url()
                                    .getQuery())
                            .isEqualTo("signature=" + (i + 2));
                    assertThat(source.imageInfo()).isNotNull();
                    assertThat(source.httpSourceCacheStatus()).isEqualTo("disk-hit");
                    assertThat(source.httpSourceLease().path()).isNotNull();
                    var tile = loader.loadImage(source.imageInfo(), source, new ImageSize(16, 16));
                    assertThat(tile.getWidth()).isEqualTo(16);
                    assertThat(tile.getHeight()).isEqualTo(16);
                    assertThat(tile.writeToMemory().byteSize()).isGreaterThan(0);
                }
            }
            assertThat(downloads).hasValue(1);
            try (Arena request = Arena.ofConfined()) {
                var source = loader(dir, runtime, cache, request).resolve("remote-image", "W/\"image-v1\"", null);
                assertThat(source.cacheInfo().eTag()).isEqualTo("\"image-v1\"");
                assertThat(((HttpResolvedImage) source.resolvedImage()).headers())
                        .isNull();
            }
        } finally {
            server.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpClient clientReturningImage(byte[] bytes) throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(ignored -> {
                    HttpResponse<InputStream> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(200);
                    when(response.headers())
                            .thenReturn(HttpHeaders.of(
                                    Map.of("Content-Length", List.of("" + bytes.length), "ETag", List.of("\"origin\"")),
                                    (name, value) -> true));
                    when(response.body()).thenReturn(new ByteArrayInputStream(bytes));
                    return response;
                });
        return client;
    }

    @Test
    void requestArenaKeepsTheBodyPinnedUntilLazyDecodingFinishes(@TempDir Path dir) throws Exception {
        byte[] bytes = Files.readAllBytes(IMAGES.resolve("embedded_icc_colorspace.jpg"));
        HttpClient client = clientReturningImage(bytes);
        var settings = new HttpSourceCacheConfig(
                true,
                dir,
                DataSize.ofBytes(bytes.length),
                Duration.ofMinutes(1),
                DataSize.ofBytes(bytes.length),
                true,
                Duration.ofDays(7));
        URI first = URI.create("https://example.com/first");
        URI other = URI.create("https://example.com/other");
        ExtensionRuntime runtime = mock(ExtensionRuntime.class);
        when(runtime.resolve(any(), any(), any()))
                .thenAnswer(ignored -> new ImageSource("remote-image", new HttpResolvedImage(first, null), null, null));
        try (var cache = new HttpSourceCache(settings, dir, client, Clock.systemUTC(), Duration.ofSeconds(30))) {
            Path pinnedPath;
            try (Arena request = Arena.ofConfined()) {
                var loader = loader(dir, runtime, cache, request);
                var source = loader.resolve("remote-image", null, null);
                var info = loader.getImageInfo(source);
                var tile = loader.loadImage(info, source, new ImageSize(16, 16));
                pinnedPath = source.httpSourceLease().path();
                try (var bypass = cache.acquire(other, null, null)) {
                    assertThat(bypass.cacheStatusHeaderValue()).isEqualTo("bypass=capacity");
                }
                assertThat(Files.exists(pinnedPath)).isTrue();
                // Evaluate the native operation after eviction pressure, while the arena owns the pin.
                assertThat(tile.writeToMemory().byteSize()).isGreaterThan(0);
            }
            try (var replacement = cache.acquire(other, null, null)) {
                assertThat(replacement.cacheStatusHeaderValue()).isEqualTo("miss");
                assertThat(Files.exists(pinnedPath)).isFalse();
            }
        }
    }

    @Test
    void resolverVersionChangeCannotReuseOldBodyOrMetadata(@TempDir Path dir) throws Exception {
        byte[] bytes = Files.readAllBytes(IMAGES.resolve("embedded_icc_colorspace.jpg"));
        HttpClient client = clientReturningImage(bytes);
        var settings = new HttpSourceCacheConfig(
                true,
                dir,
                DataSize.ofBytes(bytes.length * 2L),
                Duration.ofMinutes(1),
                DataSize.ofBytes(bytes.length),
                true,
                Duration.ofDays(7));
        ExtensionRuntime runtime = mock(ExtensionRuntime.class);
        try (var cache = new HttpSourceCache(settings, dir, client, Clock.systemUTC(), Duration.ofSeconds(30))) {
            for (String version : List.of("resolver-v1", "resolver-v2")) {
                when(runtime.resolve(any(), any(), any()))
                        .thenAnswer(ignored -> httpSource(new CacheInfo(version, null)));
                try (Arena request = Arena.ofConfined()) {
                    var loader = loader(dir, runtime, cache, request);
                    var source = loader.resolve("remote-image", null, null);
                    assertThat(source.httpSourceLease()).isNull();
                    assertThat(source.imageInfo()).isNull();
                    assertThat(loader.getImageInfo(source)).isNotNull();
                    assertThat(source.httpSourceCacheStatus()).isEqualTo("miss");
                    assertThat(source.cacheInfo().eTag()).isEqualTo(version);
                }
            }
        }
        verify(client, org.mockito.Mockito.times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
