package dev.mdz.wolpi.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mdz.wolpi.extension.GraalContextSupplier;
import dev.mdz.wolpi.validation.model.ValidationResult.ValidationFailure;
import dev.mdz.wolpi.validation.model.ValidationResult.ValidationSuccess;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// The validation runner executes the IIIF validator through GraalPy and converts its result objects
/// into [dev.mdz.wolpi.validation.model.ValidationResult] records.
class ValidationResultMappingTest {
    private static Context context;

    @BeforeAll
    static void setup() {
        context = new GraalContextSupplier(null).getPythonContext(null, null);
    }

    @AfterAll
    static void teardown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("should map a successful validation result")
    void mapsSuccess() {
        var py = context.eval("python", """
                class ValidationSuccess:
                    def __init__(self):
                        self.details = "ok"
                ValidationSuccess()
                """);

        var result = py.as(ValidationSuccess.class);

        assertThat(result.details()).isEqualTo("ok");
    }

    @Test
    @DisplayName("should map a failed validation result including its url")
    void mapsFailureWithUrl() {
        // Mirrors iiif_validator.tests.test.ValidationFailure, which is a NamedTuple
        var py = context.eval("python", """
                from typing import NamedTuple

                class ValidationFailure(NamedTuple):
                    expected: str
                    received: str
                    url: str
                    success: bool = False
                    details: str = "Validation failed"

                ValidationFailure(
                    "200",
                    "500",
                    "http://localhost:8080/v3/foo/full/max/0/default.jpg",
                    False,
                    "boom",
                )
                """);

        var result = py.as(ValidationFailure.class);

        assertThat(result.expected()).isEqualTo("200");
        assertThat(result.received()).isEqualTo("500");
        assertThat(result.url()).isEqualTo("http://localhost:8080/v3/foo/full/max/0/default.jpg");
        assertThat(result.details()).isEqualTo("boom");
    }

    @Test
    @DisplayName("should map a failed validation result whose url is a placeholder")
    void mapsFailureWithPlaceholderUrl() {
        // The validator reports `<unknown>` when it cannot determine the request it failed on. A URL
        // type cannot represent that, which used to abort startup with a ClassCastException instead
        // of reporting the validation results.
        var py = context.eval("python", """
                from typing import NamedTuple

                class ValidationFailure(NamedTuple):
                    expected: str
                    received: str
                    url: str
                    success: bool = False
                    details: str = "Validation failed"

                ValidationFailure("200", "500", "<unknown>", False, "boom")
                """);

        var result = py.as(ValidationFailure.class);

        assertThat(result.url()).isEqualTo("<unknown>");
        assertThat(result.details()).isEqualTo("boom");
    }
}
