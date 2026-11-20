package dev.mdz.wolpi.exceptions;

import java.io.IOException;

/// Signals that the HTTP source cache directory is already owned by another Wolpi instance.
///
/// Callers distinguish this from other initialization failures because it is the one conflict that
/// an operational CLI subcommand can work around by using a throwaway directory, whereas a server
/// instance must never share a cache directory with another instance.
public class CacheDirectoryLockedException extends IOException {
    public CacheDirectoryLockedException(String message) {
        super(message);
    }

    public CacheDirectoryLockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
