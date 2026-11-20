package dev.mdz.wolpi.validation.model;

public sealed interface ValidationResult {
    record ValidationSuccess(String details) implements ValidationResult {}

    /// A failed validation test.
    ///
    /// `url` is the request the validator reported the failure for. It is only used for reporting and
    /// deliberately a [String]: the validator is free to put placeholders such as `<unknown>` there,
    /// which would make a stricter type fail to convert and hide the actual test results.
    record ValidationFailure(String expected, String received, String url, String details)
            implements ValidationResult {}
}
