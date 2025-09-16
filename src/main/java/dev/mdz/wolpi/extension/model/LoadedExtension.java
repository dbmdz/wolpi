package dev.mdz.wolpi.extension.model;

import java.util.Set;
import org.graalvm.polyglot.Source;
import org.jspecify.annotations.Nullable;

/// Container for a loaded extension, contains all the necessary information to run it.
///
/// Language-specific implementations require some additional information, so this is a sealed
/// interface and not a common record class.
public sealed interface LoadedExtension permits JSLoadedExtension, PythonLoadedExtension {
  /// Returns the GraalVM [Source] object to load the extension from.
  Source source();

  /// Metadata about the extension, returned by its `info` hook.
  ExtensionInfo extensionInfo();

  /// The set of hooks implemented by the extension.
  Set<ExtensionHooks> implementedHooks();

  /// The guest context for this extension, if available.
  @Nullable ExtensionGuestContext guestContext();
}
