package dev.mdz.wolpi.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.config.WolpiConfig.HttpConfig;
import dev.mdz.wolpi.exceptions.HttpStatusException;
import dev.mdz.wolpi.image.ImageLoader;
import dev.mdz.wolpi.image.ImageProcessor;
import dev.mdz.wolpi.model.BinaryResolvedImage;
import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSize;
import dev.mdz.wolpi.model.ImageSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    public void testImageUpstreamNotModifiedIsReturnedToClient() throws Exception {
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
