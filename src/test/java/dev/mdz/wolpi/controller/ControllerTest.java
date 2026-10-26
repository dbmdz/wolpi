package dev.mdz.wolpi.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mdz.wolpi.config.HttpSourceCacheConfig;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.config.WolpiConfig.HttpConfig;
import dev.mdz.wolpi.exceptions.HttpStatusException;
import dev.mdz.wolpi.image.ImageLoader;
import dev.mdz.wolpi.image.ImageProcessor;
import dev.mdz.wolpi.model.BinaryResolvedImage;
import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.HttpSourceLease;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSize;
import dev.mdz.wolpi.model.ImageSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ControllerTest {
    @MockitoSpyBean
    WolpiConfig wolpiConfig;

    @MockitoSpyBean
    ImageProcessor imageProcessor;

    @MockitoSpyBean
    ImageLoader imageLoader;

    @Autowired
    MockMvc mockMvc;

    @ParameterizedTest
    @CsvSource({"image,true", "info,true", "image,false", "info,false"})
    public void testCacheControlHeaders(String endpoint, boolean hasCustom) throws Exception {
        if (hasCustom) {
            when(wolpiConfig.cacheControlHeaders())
                    .thenReturn(new WolpiConfig.CacheControlHeaders("custom-info-value", "custom-image-value"));
        }
        String url = endpoint.equals("image")
                ? "/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.jpg"
                : "/v3/67352ccc-d1b0-11e1-89ae-279075081939/info.json";
        String expectedValue;
        if (hasCustom) {
            expectedValue = endpoint.equals("image") ? "custom-image-value" : "custom-info-value";
        } else {
            expectedValue = "public, max-age=604800, must-revalidate";
        }
        mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", expectedValue));
    }

    @Test
    public void testInfoJsonETagIsWeak() throws Exception {
        stubSourceWithCacheInfo();

        mockMvc.perform(get("/v3/cache-test/info.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "W/\"cache-etag\""));
    }

    @Test
    public void testWeakETagCanRevalidateInfoJson() throws Exception {
        stubSourceWithCacheInfo();

        MvcResult result = mockMvc.perform(get("/v3/cache-test/info.json"))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(get("/v3/cache-test/info.json")
                        .header("If-None-Match", result.getResponse().getHeader("ETag")))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", result.getResponse().getHeader("ETag")));
    }

    private void stubSourceWithCacheInfo() {
        var source = new ImageSource(
                "cache-test",
                new BinaryResolvedImage(new byte[] {0}),
                null,
                new CacheInfo("cache-etag", Instant.parse("2026-01-01T00:00:00Z")));
        var info = new ImageInfo(new ImageSize(1, 1), List.of(), List.of());

        doReturn(true).when(imageLoader).authorize(eq("cache-test"), any(), any());
        doReturn(source).when(imageLoader).resolve(eq("cache-test"), any(), any());
        doReturn(info).when(imageLoader).getImageInfo(source);
        doReturn(Map.of("id", "cache-test", "type", "ImageService3"))
                .when(imageLoader)
                .getImageInfoJson(eq("cache-test"), eq(info), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disk-hit", "stale", "bypass=unknown-length"})
    public void testHttpSourceCacheDiagnostics(String value) throws Exception {
        stubHttpSourceCacheStatus(value);
        mockMvc.perform(get("/v3/cache-test/info.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Wolpi-Http-Source-Cache", value))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-Wolpi-Http-Source-Cache"));
    }

    @Test
    public void testImageResponseIncludesHttpSourceCacheDiagnostic(@TempDir Path directory) throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
        Path cachedBody = directory.resolve("cached.body");
        Files.write(cachedBody, png.toByteArray());
        var size = new ImageSize(1, 1);
        var source = new ImageSource(
                "cache-test",
                new HttpResolvedImage(URI.create("https://example.com/image"), null),
                new ImageInfo("png", size, List.of(size), List.of()),
                null);
        HttpSourceLease access = mock(HttpSourceLease.class);
        when(access.path()).thenReturn(cachedBody);
        when(access.canReopenBody()).thenReturn(true);
        when(access.responseHeaders()).thenReturn(new HttpHeaders());
        when(access.cacheStatusHeaderValue()).thenReturn("disk-hit");
        source.setHttpSourceLease(access);
        doReturn(true).when(imageLoader).authorize(eq("cache-test"), any(), any());
        doReturn(source).when(imageLoader).resolve(eq("cache-test"), any(), any());
        mockMvc.perform(get("/v3/cache-test/full/max/0/default.jpg"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Wolpi-Http-Source-Cache", "disk-hit"));
    }

    @Test
    public void testHttpSourceCacheDiagnosticOnConditional304() throws Exception {
        stubHttpSourceCacheStatus("disk-hit");
        mockMvc.perform(get("/v3/cache-test/info.json").header("If-None-Match", "W/\"cache-etag\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("X-Wolpi-Http-Source-Cache", "disk-hit"));
    }

    @Test
    public void testHttpSourceCacheDiagnosticsCanBeDisabled() throws Exception {
        stubHttpSourceCacheStatus("bypass=unknown-length");
        var defaults = HttpSourceCacheConfig.defaults();
        when(wolpiConfig.httpSourceCache())
                .thenReturn(new HttpSourceCacheConfig(
                        defaults.enabled(),
                        defaults.directory(),
                        defaults.maxSize(),
                        defaults.maxFreshness(),
                        defaults.maxResourceSize(),
                        false,
                        defaults.staleIfError()));
        mockMvc.perform(get("/v3/cache-test/info.json"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Wolpi-Http-Source-Cache"))
                .andExpect(header().doesNotExist("Access-Control-Expose-Headers"));
    }

    @ParameterizedTest
    @CsvSource({"info.json,true", "info.json,false", "full/max/0/default.jpg,true"})
    public void revalidatedSourceDoesNotDecideTheClientResponse(String endpoint, boolean clientMatches)
            throws Exception {
        var source = stubHttpSource();
        HttpSourceLease lease = mock(HttpSourceLease.class);
        when(lease.cacheStatusHeaderValue()).thenReturn("revalidated");
        // Both the lease and validators become known during metadata loading, not resolve().
        doAnswer(call -> {
                    source.setHttpSourceLease(lease);
                    source.updateSourceValidators(new CacheInfo("v1", null));
                    return new ImageInfo(new ImageSize(1, 1), List.of(), List.of());
                })
                .when(imageLoader)
                .getImageInfo(source);
        var policy = new WolpiConfig.CacheControlHeaders("public, max-age=17", "public, max-age=23");
        when(wolpiConfig.cacheControlHeaders()).thenReturn(policy);
        mockMvc.perform(get("/v3/cache-test/" + endpoint)
                        .header("If-None-Match", clientMatches ? "W/\"v1\"" : "W/\"old\""))
                .andExpect(clientMatches ? status().isNotModified() : status().isOk())
                .andExpect(header().string("ETag", "W/\"v1\""))
                .andExpect(header().string(
                                "Cache-Control", endpoint.equals("info.json") ? policy.infoJson() : policy.images()))
                .andExpect(header().string("X-Wolpi-Http-Source-Cache", "revalidated"));
    }

    @Test
    public void testSourceTimeoutIsAGatewayTimeout() throws Exception {
        stubSourceWithCacheInfo();
        doThrow(new HttpTimeoutException("deadline exceeded"))
                .when(imageProcessor)
                .processImage(any(), any());
        mockMvc.perform(get("/v3/cache-test/full/max/0/default.jpg").header("Origin", "https://client.example"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://client.example"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"info.json", "full/max/0/default.jpg"})
    public void sourceAcquisitionTimeoutIsAGatewayTimeout(String endpoint) throws Exception {
        stubSourceWithCacheInfo();
        doThrow(new HttpStatusException("HTTP source acquisition deadline exceeded", 504, null))
                .when(imageLoader)
                .getImageInfo(any(ImageSource.class));
        mockMvc.perform(get("/v3/cache-test/" + endpoint)).andExpect(status().isGatewayTimeout());
    }

    @Test
    public void testNonHttpSourcesHaveNoSourceCacheHeader() throws Exception {
        stubSourceWithCacheInfo();
        mockMvc.perform(get("/v3/cache-test/info.json"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Wolpi-Http-Source-Cache"));
    }

    private ImageSource stubHttpSourceCacheStatus(String value) {
        var source = stubHttpSource();
        source.updateSourceValidators(new CacheInfo("cache-etag", null));
        HttpSourceLease access = mock(HttpSourceLease.class);
        when(access.cacheStatusHeaderValue()).thenReturn(value);
        source.setHttpSourceLease(access);
        return source;
    }

    private ImageSource stubHttpSource() {
        var source = new ImageSource(
                "cache-test", new HttpResolvedImage(URI.create("https://example.com/image"), null), null, null);
        var info = new ImageInfo(new ImageSize(1, 1), List.of(), List.of());
        doReturn(true).when(imageLoader).authorize(eq("cache-test"), any(), any());
        doReturn(source).when(imageLoader).resolve(eq("cache-test"), any(), any());
        doReturn(info).when(imageLoader).getImageInfo(source);
        doReturn(Map.of("id", "cache-test", "type", "ImageService3"))
                .when(imageLoader)
                .getImageInfoJson(eq("cache-test"), eq(info), any(), any());
        return source;
    }

    @Test
    public void testInfoJsonWithCustomBaseUri() throws Exception {
        when(wolpiConfig.http())
                .thenReturn(new HttpConfig("localhost", 31337, 8, 200, 200, "https://example.com/iiif"));
        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939/info.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("https://example.com/iiif/v3/67352ccc-d1b0-11e1-89ae-279075081939"));
    }

    @Test
    public void testInfoJsonWithEmptyBaseUriFallsBackToRequestUrl() throws Exception {
        when(wolpiConfig.http()).thenReturn(new HttpConfig("localhost", 31337, 8, 200, 200, ""));
        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939/info.json")
                        .header("Host", "example.com:8217"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("http://example.com:8217/v3/67352ccc-d1b0-11e1-89ae-279075081939"));
    }

    @Test
    public void testImageWithLinkCanonicalHeaderAndCustomBaseUri() throws Exception {
        when(wolpiConfig.http())
                .thenReturn(new HttpConfig("localhost", 31337, 8, 200, 200, "https://example.com/iiif"));
        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.jpg"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Link",
                                        containsString(
                                                "<https://example.com/iiif/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.jpg>; rel=\"canonical\"")));
    }

    @Test
    public void testImageWithLinkCanonicalHeaderAndEmptyBaseUriFallsBackToRequestUrl() throws Exception {
        when(wolpiConfig.http()).thenReturn(new HttpConfig("localhost", 31337, 8, 200, 200, ""));
        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.jpg")
                        .header("Host", "example.com:8217"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Link",
                                        containsString(
                                                "<http://example.com:8217/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.jpg>; rel=\"canonical\"")));
    }

    @Test
    public void testImageCanonicalRedirectReturns301AndCanonicalLocation() throws Exception {
        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/color.jpg"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string(
                                "Location",
                                containsString("/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.jpg")));
    }

    @Test
    public void testBaseUriRedirectUsesCustomBaseUri() throws Exception {
        when(wolpiConfig.http())
                .thenReturn(new HttpConfig("localhost", 31337, 8, 200, 200, "https://example.com/iiif"));
        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(
                                "Location",
                                "https://example.com/iiif/v3/67352ccc-d1b0-11e1-89ae-279075081939/info.json"));
    }

    @Test
    public void testImageCanonicalRedirectIncludesContextPath() throws Exception {
        mockMvc.perform(get("/iiif/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/color.jpg")
                        .contextPath("/iiif"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string(
                                "Location",
                                containsString(
                                        "/iiif/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.jpg")));
    }

    @Test
    public void testImageCanonicalRedirectUsesCustomBaseUri() throws Exception {
        when(wolpiConfig.http())
                .thenReturn(new HttpConfig("localhost", 31337, 8, 200, 200, "https://example.com/iiif"));
        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/color.jpg"))
                .andExpect(status().isMovedPermanently())
                .andExpect(
                        header().string(
                                        "Location",
                                        "https://example.com/iiif/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.jpg"));
    }

    @Test
    public void testHttpStatusExceptionIsReturnedToClient() throws Exception {
        doThrow(new HttpStatusException("Failed to load image", 404, null))
                .when(imageProcessor)
                .processImage(any(), any());

        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.jpg"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Failed to load image"));
    }

    @Test
    public void testExplicitHttpStatusExceptionHeadersArePreserved() throws Exception {
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.setETag("\"upstream-etag\"");
        upstreamHeaders.setVary(List.of("Accept"));
        doThrow(new HttpStatusException("Image not modified", 304, null, upstreamHeaders))
                .when(imageProcessor)
                .processImage(any(), any());

        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.jpg"))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"upstream-etag\""));
    }

    @Test
    public void testInfoJsonUpstreamNotFoundIsReturnedToClient() throws Exception {
        doThrow(new HttpStatusException("Failed to load image", 404, null, new HttpHeaders()))
                .when(imageLoader)
                .getImageInfo(any());

        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939/info.json"))
                .andExpect(status().isNotFound());
    }
}
