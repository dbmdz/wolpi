package dev.mdz.wolpi.extension.model;

import dev.mdz.wolpi.config.ExtensionConfig;
import dev.mdz.wolpi.extension.PyPiInstaller.EntryPoint;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.graalvm.polyglot.Source;
import org.jspecify.annotations.Nullable;

/// A [LoadedExtension] for a Python extension.
///
/// Adds Python-specific fields required for setting up the runtime environment.
///
/// @param entryPoint optional entry point function to call to get the hooks object.
/// @param virtualEnvironment optional path to a Python virtual environment to use when running
///                           the extension.
public record PythonLoadedExtension(
        ExtensionConfig config,
        Source source,
        ExtensionInfo extensionInfo,
        @Nullable String extensionVersion,
        Set<ExtensionHooks> implementedHooks,
        @Nullable EntryPoint entryPoint,
        @Nullable Path virtualEnvironment,
        @Nullable ExtensionGuestContext guestContext,
        @Nullable Instant lastModified)
        implements LoadedExtension {
    @Override
    public Language language() {
        return Language.PYTHON;
    }
}
