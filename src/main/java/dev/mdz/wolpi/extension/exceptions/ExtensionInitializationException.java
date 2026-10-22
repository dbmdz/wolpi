package dev.mdz.wolpi.extension.exceptions;

import org.jspecify.annotations.Nullable;

/// Thrown when the set of configured extensions cannot be initialized.
///
/// Wolpi refuses to start if a configured extension fails to load, since a server with a partially
/// loaded extension set may silently serve requests that the failed extension was supposed to
/// protect (e.g. an extension implementing the `authorize` hook).
public class ExtensionInitializationException extends RuntimeException {

    public ExtensionInitializationException(String msg) {
        super(msg);
    }

    public ExtensionInitializationException(String msg, @Nullable Throwable cause) {
        super(msg, cause);
    }
}
