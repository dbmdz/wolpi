package dev.mdz.wolpi;

import dev.mdz.wolpi.validation.ImageApiValidator;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "wolpi.image-base-dir=src/test/resources/images")
@ActiveProfiles("test")
@DisplayName("WolpiApplication")
class WolpiApplicationTests {
    private static final String VALIDATION_BASE_ID = "67352ccc-d1b0-11e1-89ae-279075081939";

    @LocalServerPort
    private int port;

    // Match the CLI integration fixture so these HTTP-only checks can reuse its Spring context.
    // The full official matrix now runs through the real CLI in CliRunnerIntegrationTest.
    @MockitoSpyBean
    private ImageApiValidator imageApiValidator;

    private WebTestClient client;

    @BeforeEach
    void setupWebTestclient() {
        client =
                WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void extensionsHaveAccessToServletDerivedBaseUrl() {
        client.get()
                .uri("/v3/67352ccc-d1b0-11e1-89ae-279075081939/full/max/0/default.hdr-png")
                .exchange()
                .expectHeader()
                .valueEquals("X-Wolpi-Base-Uri", "http://localhost:" + port);
    }

    @Test
    void acceptsUrlEncodedSlashInIdentifier() {
        var uri = URI.create("http://localhost:%d/v3/%s%%2Fnested/info.json".formatted(port, VALIDATION_BASE_ID));
        client.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo("http://localhost:%d/v3/%s%%2Fnested".formatted(port, VALIDATION_BASE_ID));
    }

    @Test
    void doesNotAcceptUnencodedSlashInIdentifier() {
        var uri = URI.create("http://localhost:%d/v3/%s/nested/info.json".formatted(port, VALIDATION_BASE_ID));
        client.get().uri(uri).exchange().expectStatus().isNotFound();
    }

    @Test
    void preservesEncodedSlashInCanonicalImageRedirect() {
        var uri = URI.create(
                "http://localhost:%d/v3/%s%%2Fnested/full/max/0/color.jpg".formatted(port, VALIDATION_BASE_ID));
        client.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.MOVED_PERMANENTLY)
                .expectHeader()
                .valueEquals(
                        "Location",
                        "http://localhost:%d/v3/%s%%2Fnested/full/max/0/default.jpg"
                                .formatted(port, VALIDATION_BASE_ID));
    }
}
