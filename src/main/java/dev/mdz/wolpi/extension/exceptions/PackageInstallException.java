package dev.mdz.wolpi.extension.exceptions;

import org.jspecify.annotations.Nullable;

/// Thrown when installing a third-party package fails
public class PackageInstallException extends Exception {

  public PackageInstallException(String message) {
    super(message);
  }

  public PackageInstallException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }

  public PackageInstallException(Throwable cause) {
    super(cause);
  }
}
