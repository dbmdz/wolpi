package dev.mdz.wolpi;

import dev.mdz.wolpi.extension.GraalContextSupplier;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.validation.ImageApiValidator;
import dev.mdz.wolpi.validation.model.ValidationResult;
import dev.mdz.wolpi.validation.model.ValidationResult.ValidationFailure;
import dev.mdz.wolpi.validation.model.ValidationTest;
import java.util.stream.Stream;
import org.assertj.core.api.AbstractAssert;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

// Needed so we can generate arguments from the imageApiValidator field
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("WolpiApplication")
class WolpiApplicationTests {
    // Use PNG for validation, should be a lot faster than JP2
    private static final String VALIDATION_ID = "67352ccc-d1b0-11e1-89ae-279075081939-png";

    // NOTE: Needs to be public for @ArgumentsSource to work
    @LocalServerPort
    public int port;

    private Context graalContext;

    @Autowired
    protected ImageApiValidator imageApiValidator;

    // Keep a Graal Python Context alive for the duration of all tests to speed things up
    // considerably (~2sec overhead per test otherwise!)
    @BeforeAll
    void setupGraalContext() {
        this.graalContext = imageApiValidator.getValidationContext();
    }

    @AfterAll
    void closeGraalContext() {
        if (this.graalContext != null) {
            this.graalContext.close();
        }
        // FIXME: This test class seems to somehow mess up the Graal Engine's internal state, but
        //        only when running in Surefire (IDEA works fine). Resetting the engine seems to fix
        //        it, but I have no idea what the root cause is.
        GraalContextSupplier.resetEngine();
    }

    @DisplayName("Run official IIIF Image API validation test suite")
    @ParameterizedTest(name = "{0} ({1})")
    @ArgumentsSource(ValidationTestProvider.class)
    void validateImageApi(ValidationTest test, IIIFVersion version) {
        String baseUrl = "http://localhost:%d/v%d".formatted(port, version.value());
        for (var result : imageApiValidator.runTest(test, baseUrl, VALIDATION_ID, version, graalContext)) {
            ValidationResultAssert.assertThat(result).isSuccess();
        }
    }

    /// Generate tests from the python test classes
    public static class ValidationTestProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            WolpiApplicationTests testInstance = (WolpiApplicationTests) context.getRequiredTestInstance();
            return testInstance.imageApiValidator.getAllTests().stream()
                    .flatMap(test -> test.versions().stream().map(v -> Arguments.of(test, v)));
        }
    }

    /// Custom AssertJ assertion for ValidationResult so we can get nice error messages
    public static class ValidationResultAssert extends AbstractAssert<ValidationResultAssert, ValidationResult> {

        public ValidationResultAssert(ValidationResult actual) {
            super(actual, ValidationResultAssert.class);
        }

        public static ValidationResultAssert assertThat(ValidationResult actual) {
            return new ValidationResultAssert(actual);
        }

        public ValidationResultAssert isSuccess() {
            isNotNull();
            if (actual
                    instanceof ValidationFailure(String expected, String received, java.net.URI url, String details)) {
                failWithMessage("Validation failed: %s. Expected: '%s', but got: '%s'. URL was '%s'"
                        .formatted(details, expected, received, url));
            }
            return this;
        }
    }
}
