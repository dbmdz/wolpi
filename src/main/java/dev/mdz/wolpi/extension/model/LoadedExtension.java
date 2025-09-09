package dev.mdz.wolpi.extension.model;

import java.util.Set;
import java.util.function.Supplier;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/// Container for a loaded extension, contains all the necessary information to run it
///
/// @param lang The programming language the extension is written in
/// @param extensionInfo Metadata about the extension, returned by its `info` hook
/// @param runtimeContextSupplier A supplier that creates a new runtime context for the extension
/// @param implementedHooks The hooks that the extension implements
public record LoadedExtension(
    Language lang,
    ExtensionInfo extensionInfo,
    Supplier<RuntimeContext> runtimeContextSupplier,
    Set<ExtensionHooks> implementedHooks) {

  public RuntimeContext createRuntimeContext() {
    return runtimeContextSupplier.get();
  }

  /// Container for the runtime context of an extension, contains all the state
  /// that is bound to the lifetime of a single request.
  ///
  /// @param langContext The GraalVM Polyglot context the extension runs in
  /// @param wolpiContext The Wolpi-specific context object, available in the global scope of the
  ///                     extension to interact with Wolpi
  /// @param extensionObject The main extension object, containing the extension hooks as members
  public record RuntimeContext(
      Context langContext, ExtensionContext wolpiContext, Value extensionObject) {}

  /// Supported extension languages
  public enum Language {
    JAVASCRIPT("js"),
    PYTHON("python");

    private final String graalName;

    Language(String graalName) {
      this.graalName = graalName;
    }

    public String graalName() {
      return graalName;
    }
  }
}
