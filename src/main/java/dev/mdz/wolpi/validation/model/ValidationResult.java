package dev.mdz.wolpi.validation.model;

import java.net.URI;

public sealed interface ValidationResult {
    record ValidationSuccess(String details) implements ValidationResult {}

    record ValidationFailure(String expected, String received, URI url, String details) implements ValidationResult {}
}
