package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.extension.PyPiInstaller.EntryPoint;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.model.ExtensionGuestContext;
import dev.mdz.wolpi.extension.model.Language;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;

/// A runtime context for Python extensions.
public class PythonRuntimeContext extends RuntimeContext {
  private final Source source;
  private final @Nullable EntryPoint entryPoint;
  private final @Nullable Path venvPath;
  private final @Nullable ExtensionGuestContext guestContext;

  public PythonRuntimeContext(
      Source source,
      @Nullable EntryPoint entryPoint,
      @Nullable Path venvPath,
      @Nullable ExtensionGuestContext extensionGuestContext)
      throws ExtensionLoadException {
    this.source = source;
    this.entryPoint = entryPoint;
    this.venvPath = venvPath;
    this.guestContext = extensionGuestContext;
    super();
  }

  @Override
  protected Context getGraalContext() {
    return GraalContextSupplier.getPythonContext(venvPath, guestContext);
  }

  /// Evaluates the extension source and returns the object containing its hooks.
  ///
  /// The hooks object can either be returned by an entry point function (for packaged extensions)
  /// or simply be the top-level scope where all hook functions are defined (for single-file
  /// extensions).
  @Override
  protected Value getExtensionObject() throws ExtensionLoadException {
    this.langContext.enter();
    try {
      langContext.eval(source);
      var bindings = langContext.getBindings("python");

      Value hooks;
      if (entryPoint != null) {
        if (!bindings.hasMember(entryPoint.function())) {
          throw new IllegalArgumentException(
              "Entry point function '%s' not found in extension.".formatted(entryPoint.function()));
        }
        hooks = bindings.getMember(entryPoint.function()).execute();
      } else {
        hooks = bindings;
      }

      var functions =
          hooks.getMemberKeys().stream()
              .filter(key -> !key.startsWith("_") && hooks.getMember(key).canExecute())
              .toList();

      if (functions.isEmpty()) {
        throw new ExtensionLoadException("Extension did not define any top-level functions.");
      }
      return hooks;
    } finally {
      this.langContext.leave();
    }
  }

  @Override
  public Language getLang() {
    return Language.PYTHON;
  }
}
