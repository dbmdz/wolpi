package dev.mdz.wolpi.exceptions;

/// Thrown when an error occurs during the execution of an extension.
public class ExtensionExecutionException extends RuntimeException {
    public ExtensionExecutionException(String msg) {
        super(msg);
    }

    public ExtensionExecutionException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
