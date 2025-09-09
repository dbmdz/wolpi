package dev.mdz.wolpi.extension;

/// Thrown when loading an extension fails
public class ExtensionLoadException extends Exception {

  public ExtensionLoadException(String msg) {
    super(msg);
  }

  public ExtensionLoadException(Exception e) {
    super(e);
  }

  public ExtensionLoadException(String msg, Exception e) {
    super(msg, e);
  }
}
