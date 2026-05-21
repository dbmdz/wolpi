package dev.mdz.wolpi.extension.model;

import dev.mdz.wolpi.config.ExtensionConfig;
import java.time.Instant;
import java.util.Set;
import org.graalvm.polyglot.Source;
import org.jspecify.annotations.Nullable;

/// A [LoadedExtension] for a JavaScript extension.
///
/// No extra fields beyond the ones in [LoadedExtension].
public record JSLoadedExtension(
        ExtensionConfig config,
        Source source,
        ExtensionInfo extensionInfo,
        @Nullable String extensionVersion,
        Set<ExtensionHooks> implementedHooks,
        @Nullable ExtensionGuestContext guestContext,
        @Nullable Instant lastModified)
        implements LoadedExtension {
    @Override
    public Language language() {
        return Language.JAVASCRIPT;
    }
}
