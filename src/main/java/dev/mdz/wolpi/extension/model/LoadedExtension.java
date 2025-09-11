package dev.mdz.wolpi.extension.model;

import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/// Container for a loaded extension, contains all the necessary information to run it
///
/// @param lang The programming language the extension is written in
/// @param extensionInfo Metadata about the extension, returned by its `info` hook
/// @param extensionVersion The version of the extension, if available
/// @param runtimeContextSupplier A supplier that creates a new runtime context for the extension
/// @param implementedHooks The hooks that the extension implements
public record LoadedExtension(
    Language lang,
    ExtensionInfo extensionInfo,
    @Nullable String extensionVersion,
    Supplier<RuntimeContext> runtimeContextSupplier,
    Set<ExtensionHooks> implementedHooks) {

  public RuntimeContext createRuntimeContext() {
    return runtimeContextSupplier.get();
  }
}
