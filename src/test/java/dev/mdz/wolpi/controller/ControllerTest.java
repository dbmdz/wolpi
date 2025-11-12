package dev.mdz.wolpi.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.config.WolpiConfig.HttpConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
public class ControllerTest {
    @MockitoSpyBean
    WolpiConfig wolpiConfig;

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
    public void testInfoJsonWithCustomBaseUri() throws Exception {
        when(wolpiConfig.http())
                .thenReturn(new HttpConfig("localhost", 31337, 8, 200, 200, "https://example.com/iiif"));
        mockMvc.perform(get("/v3/67352ccc-d1b0-11e1-89ae-279075081939/info.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("https://example.com/iiif/v3/67352ccc-d1b0-11e1-89ae-279075081939"));
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
}
