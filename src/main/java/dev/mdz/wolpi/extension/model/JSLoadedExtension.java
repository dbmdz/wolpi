package dev.mdz.wolpi.extension.model;

import java.util.Set;
import org.graalvm.polyglot.Source;
import org.jspecify.annotations.Nullable;

/// A [LoadedExtension] for a JavaScript extension.
///
/// No extra fields beyond the ones in [LoadedExtension].
public record JSLoadedExtension(
    Source source,
    ExtensionInfo extensionInfo,
    @Nullable String extensionVersion,
    Set<ExtensionHooks> implementedHooks,
    @Nullable ExtensionGuestContext guestContext)
    implements LoadedExtension {}
