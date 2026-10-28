package dev.mdz.wolpi.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import dev.mdz.wolpi.config.ExtensionConfig;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.ExtensionRegistry;
import dev.mdz.wolpi.extension.NpmInstaller;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.validation.CliRunner.InstallExtensionsCommand;
import dev.mdz.wolpi.validation.CliRunner.ValidationCommand;
import dev.mdz.wolpi.validation.model.ValidationResult;
import dev.mdz.wolpi.validation.model.ValidationResult.ValidationFailure;
import dev.mdz.wolpi.validation.model.ValidationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import picocli.CommandLine;

/// Run the real CLI conformance pass once and report each official assertion separately. Source
/// bindings use a real filesystem image: built-in validation images bypass authorization.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "wolpi.image-base-dir=src/test/resources/images")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliRunnerIntegrationTest {
    private static final String VALIDATION_ID = "67352ccc-d1b0-11e1-89ae-279075081939-png";
    private static final String REAL_IMAGE = "embedded_icc_colorspace.jpg";

    @Autowired
    private CommandLine.IFactory springFactory;

    @Autowired
    private ValidationCommand validationCommand;

    @Autowired
    private WolpiConfig config;

    @Autowired
    private ExtensionRegistry registry;

    @Autowired
    private NpmInstaller npmInstaller;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoSpyBean
    private ImageApiValidator imageApiValidator;

    @LocalServerPort
    private int port;

    private final Map<IIIFVersion, Map<String, List<ValidationResult>>> results = new EnumMap<>(IIIFVersion.class);
    private WebTestClient client;

    @BeforeAll
    @SuppressWarnings("unchecked")
    void runConformanceThroughCli() throws Exception {
        client =
                WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        // Observe real results; do not replace validation or fabricate successes. The production
        // runner selects applicable cases; run any omitted official cases explicitly below.
        doAnswer(invocation -> {
                    var actual = (Map<ValidationTest, List<ValidationResult>>) invocation.callRealMethod();
                    var byImport = new HashMap<String, List<ValidationResult>>();
                    actual.forEach((test, values) -> byImport.put(test.pythonImportString(), values));
                    results.put(invocation.getArgument(2, IIIFVersion.class), byImport);
                    return actual;
                })
                .when(imageApiValidator)
                .runTests(anyString(), eq(VALIDATION_ID), any(IIIFVersion.class), eq(4));
        // The test profile intentionally excludes the startup validator. Supply only that unused
        // subcommand explicitly; validation itself still uses Spring's real wired command bean.
        var cliRunner = new CliRunner(new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == InstallExtensionsCommand.class) {
                    return cls.cast(new InstallExtensionsCommand(config, mock(ValidatingRunner.class)));
                }
                return springFactory.create(cls);
            }
        });
        var previousHandler = validationCommand.exitHandler;
        cliRunner.exitHandler = code -> {
            throw new RuntimeException("Exit with code: " + code);
        };
        validationCommand.exitHandler = cliRunner.exitHandler;
        try {
            assertThatThrownBy(() -> cliRunner.run("validate", "src/test/resources/js-extension/index.js"))
                    .hasMessage("Exit with code: 0");
        } finally {
            validationCommand.exitHandler = previousHandler;
        }
        assertThat(results).containsOnlyKeys(IIIFVersion.V2, IIIFVersion.V3);

        // Do not silently lose cases filtered out by run_tests(), compared with the original
        // application test's unconditional official matrix. Reuse one context for the remainder.
        var missing = imageApiValidator.getAllTests().stream()
                .flatMap(test -> test.versions().stream().map(version -> Map.entry(test, version)))
                .filter(entry -> !results.get(entry.getValue())
                        .containsKey(entry.getKey().pythonImportString()))
                .toList();
        if (!missing.isEmpty()) {
            try (var context = imageApiValidator.getValidationContext()) {
                for (var entry : missing) {
                    var test = entry.getKey();
                    var version = entry.getValue();
                    results.get(version)
                            .put(
                                    test.pythonImportString(),
                                    imageApiValidator.runTest(
                                            test,
                                            "http://localhost:%d/v%d".formatted(port, version.value()),
                                            VALIDATION_ID,
                                            version,
                                            context));
                }
            }
        }
    }

    @Test
    void cliValidatedBothIiifVersions() {
        for (var version : IIIFVersion.values()) {
            assertThat(results.get(version)).isNotEmpty();
        }
    }

    @DisplayName("Run official IIIF Image API validation test suite")
    @ParameterizedTest(name = "{0} ({1})")
    @ArgumentsSource(ValidationTestProvider.class)
    void validateImageApi(ValidationTest test, IIIFVersion version) {
        var actual = results.get(version).get(test.pythonImportString());
        assertThat(actual).as("Results for %s (%s)", test, version).isNotNull().isNotEmpty();
        for (var result : actual) {
            if (result instanceof ValidationFailure failure) {
                fail("Validation failed: %s. Expected: '%s', got: '%s'. URL: %s"
                        .formatted(failure.details(), failure.expected(), failure.received(), failure.url()));
            }
        }
    }

    @ParameterizedTest(name = "serves a real image through {0}")
    @ValueSource(strings = {"test.js", "test.py", "js-extension"})
    void extensionSourceHandlesRealHttpRequests(String source) throws Exception {
        if (source.equals("js-extension")) {
            assumeTrue(
                    npmInstaller.supportsPackageLiveReload(), "NPM version 10+ required for linked local extensions");
        }
        var extension = registry.loadExtension(
                new ExtensionConfig(Path.of("src/test/resources/" + source), null, null, null, false));
        var invocations = meterRegistry.counter(
                "wolpi.extension.invocations.total",
                "extension_name",
                extension.extensionInfo().name(),
                "hook_type",
                "authorize");
        double before = invocations.count();
        try (var ignored = registry.temporarilyIsolateExtension(extension)) {
            for (var version : IIIFVersion.values()) {
                String base = "/v" + version.value() + "/" + REAL_IMAGE;
                client.get()
                        .uri(base + "/info.json")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.width")
                        .isNumber();
                // IIIF 2 canonicalizes proportional scaling to width-only; IIIF 3 uses WxH.
                String size = version == IIIFVersion.V2 ? "32," : "32,57";
                byte[] image = client.get()
                        .uri(base + "/full/" + size + "/0/default.jpg")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentTypeCompatibleWith(MediaType.IMAGE_JPEG)
                        .expectBody()
                        .returnResult()
                        .getResponseBody();
                assertThat(image).isNotNull().hasSizeGreaterThan(2);
                assertThat(image[0]).isEqualTo((byte) 0xff);
                assertThat(image[1]).isEqualTo((byte) 0xd8);
                var decoded = ImageIO.read(new ByteArrayInputStream(image));
                assertThat(decoded).isNotNull();
                assertThat(decoded.getWidth()).isEqualTo(32);
                assertThat(decoded.getHeight()).isEqualTo(57);
            }
        }
        assertThat(invocations.count())
                .as("real authorization hook invocations")
                .isGreaterThanOrEqualTo(before + 4);
    }

    public static class ValidationTestProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            var instance = (CliRunnerIntegrationTest) context.getRequiredTestInstance();
            return instance.imageApiValidator.getAllTests().stream()
                    .flatMap(test -> test.versions().stream().map(version -> Arguments.of(test, version)));
        }
    }
}
