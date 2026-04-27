package dev.mdz.wolpi.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.exceptions.HttpStatusException;
import dev.mdz.wolpi.extension.ExtensionRuntime;
import dev.mdz.wolpi.metrics.WolpiMetrics;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.ImageSource;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class ImageLoaderTest {
    private static Arena arena;
    private static ImageLoader imageLoader;

    @BeforeAll
    static void setup() {
        Vips.init();
        arena = Arena.ofConfined();
        imageLoader = new ImageLoader(
                new WolpiConfig(
                        Path.of("/tmp/wolpi-test-tmp"),
                        Path.of("src/test/resources/images"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of()),
                arena,
                null,
                null,
                null,
                null);
    }

    @AfterAll
    static void teardown() {
        arena.close();
    }

    @ParameterizedTest(name = "should map ''{0}'' to format ''{1}''")
    @CsvSource({"embedded_icc_colorspace.jpg, jpeg", "embedded_icc_colorspace.jp2, jp2k"})
    void testImageInfoFormatExtraction(String imageName, String expectedFormat)
            throws IOException, InterruptedException {
        VImage image = VImage.newFromFile(
                arena, Path.of("src/test/resources/images", imageName).toString());

        var info = imageLoader.getImageInfo(new ImageSource(imageName, null, null, null), image);

        assertThat(info).isNotNull();
        assertThat(info.format()).isEqualTo(expectedFormat);
    }

    @Test
    void shouldThrowHttpStatusExceptionOnHttpErrors() throws IOException, InterruptedException {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        InputStream responseBody = mock(InputStream.class);
        when(response.statusCode()).thenReturn(404);
        when(response.body()).thenReturn(responseBody);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        ImageLoader httpImageLoader = new ImageLoader(
                new WolpiConfig(
                        Path.of("/tmp/wolpi-test-tmp"),
                        Path.of("src/test/resources/images"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of()),
                arena,
                httpClient,
                null,
                null,
                null);

        ImageSource source = new ImageSource(
                "remote-image", new HttpResolvedImage(URI.create("https://example.com/image.jp2"), null), null, null);

        assertThatThrownBy(() -> httpImageLoader.loadImage(source))
                .isInstanceOf(HttpStatusException.class)
                .extracting("httpStatusCode")
                .isEqualTo(404);
    }

    @Test
    void shouldThrowHttpStatusExceptionOnNotModified() throws IOException, InterruptedException {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        InputStream responseBody = mock(InputStream.class);
        when(response.statusCode()).thenReturn(304);
        when(response.body()).thenReturn(responseBody);
        when(response.headers())
                .thenReturn(
                        HttpHeaders.of(Map.of("ETag", java.util.List.of("\"upstream-etag\"")), (name, value) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        ImageLoader httpImageLoader = new ImageLoader(
                new WolpiConfig(
                        Path.of("/tmp/wolpi-test-tmp"),
                        Path.of("src/test/resources/images"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of()),
                arena,
                httpClient,
                null,
                null,
                null);

        ImageSource source = new ImageSource(
                "remote-image", new HttpResolvedImage(URI.create("https://example.com/image.jp2"), null), null, null);

        assertThatThrownBy(() -> httpImageLoader.loadImage(source))
                .isInstanceOf(HttpStatusException.class)
                .extracting("httpStatusCode")
                .isEqualTo(304);
    }

    @Test
    void shouldForwardConditionalHeadersFromResolveToUpstreamRequest() throws IOException, InterruptedException {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        InputStream responseBody = mock(InputStream.class);
        when(response.statusCode()).thenReturn(404);
        when(response.body()).thenReturn(responseBody);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (_, _) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        ExtensionRuntime runtime = mock(ExtensionRuntime.class);
        when(runtime.resolve("remote-image", "client-etag", null))
                .thenReturn(new ImageSource(
                        "remote-image",
                        new HttpResolvedImage(URI.create("https://example.com/image.jp2"), Map.of()),
                        null,
                        null));

        ImageLoader httpImageLoader = new ImageLoader(
                new WolpiConfig(
                        Path.of("/tmp/wolpi-test-tmp"),
                        Path.of("src/test/resources/images"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of()),
                arena,
                httpClient,
                runtime,
                null,
                null);

        ImageSource source = httpImageLoader.resolve("remote-image", "client-etag", null);

        assertThatThrownBy(() -> httpImageLoader.loadImage(source)).isInstanceOf(HttpStatusException.class);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requestCaptor.getValue().headers().firstValue("If-None-Match"))
                .contains("client-etag");
    }

    @Test
    void shouldResolveFilesystemIdentifierInsideBaseDirectory(@TempDir Path tempDir) throws IOException {
        Path baseDir = tempDir.resolve("images");
        Files.createDirectories(baseDir);
        Path imagePath = baseDir.resolve("image.jp2");
        Files.writeString(imagePath, "test image placeholder");

        ImageSource source = filesystemFallbackLoader(baseDir).resolve("image.jp2", null, null);

        assertThat(source).isNotNull();
        assertThat(source.resolvedImage()).isInstanceOf(FilesystemResolvedImage.class);
        assertThat(((FilesystemResolvedImage) source.resolvedImage()).path()).isEqualTo(imagePath.toRealPath());
    }

    @Test
    void shouldRejectFilesystemTraversalOutsideBaseDirectory(@TempDir Path tempDir) throws IOException {
        Path baseDir = tempDir.resolve("images");
        Files.createDirectories(baseDir);
        Files.writeString(tempDir.resolve("secret.jp2"), "secret");

        ImageSource source = filesystemFallbackLoader(baseDir).resolve("../secret.jp2", null, null);

        assertThat(source).isNull();
    }

    @Test
    void shouldRejectAbsoluteFilesystemPathOutsideBaseDirectory(@TempDir Path tempDir) throws IOException {
        Path baseDir = tempDir.resolve("images");
        Files.createDirectories(baseDir);
        Path secret = tempDir.resolve("secret.jp2");
        Files.writeString(secret, "secret");

        ImageSource source = filesystemFallbackLoader(baseDir).resolve(secret.toString(), null, null);

        assertThat(source).isNull();
    }

    @Test
    void shouldRejectFilesystemSymlinkOutsideBaseDirectory(@TempDir Path tempDir) throws IOException {
        Path baseDir = tempDir.resolve("images");
        Files.createDirectories(baseDir);
        Path secret = tempDir.resolve("secret.jp2");
        Files.writeString(secret, "secret");
        Path link = baseDir.resolve("linked.jp2");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symbolic links are not available in this environment");
        }

        ImageSource source = filesystemFallbackLoader(baseDir).resolve("linked.jp2", null, null);

        assertThat(source).isNull();
    }

    private ImageLoader filesystemFallbackLoader(Path baseDir) {
        ExtensionRuntime runtime = mock(ExtensionRuntime.class);
        when(runtime.resolve(any(), any(), any())).thenReturn(null);
        return new ImageLoader(
                new WolpiConfig(
                        Path.of("/tmp/wolpi-test-tmp"),
                        baseDir,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of()),
                arena,
                null,
                runtime,
                null,
                mock(WolpiMetrics.class));
    }
}
