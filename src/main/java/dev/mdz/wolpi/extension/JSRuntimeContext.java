package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.model.ExtensionGuestContext;
import dev.mdz.wolpi.extension.model.Language;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;

/// A runtime context for JavaScript extensions.
public class JSRuntimeContext extends RuntimeContext {
  private final Source source;
  private final @Nullable ExtensionGuestContext guestContext;

  public JSRuntimeContext(Source source, @Nullable ExtensionGuestContext guestContext)
      throws ExtensionLoadException {
    this.source = source;
    this.guestContext = guestContext;
    super();
  }

  @Override
  protected Context getGraalContext() {
    return GraalContextSupplier.getJsContext(guestContext);
  }

  /// Evaluates the extension source and returns the object containing its hooks.
  ///
  /// The hooks object can either be the default export, or the full set of named exports.
  @Override
  protected Value getExtensionObject() throws ExtensionLoadException {
    this.langContext.enter();
    try {
      var exports = this.langContext.eval(this.source);

      if (exports == null || exports.isNull()) {
        throw new ExtensionLoadException("Extension did not export anything.");
      }

      // We support both named exports (where the hooks are individually exported) and default
      // exports (where a single object containing the hooks is exported as the default).
      // The default export is only used if it is the only export, otherwise we assume that the
      // other exports are the hooks.
      if (exports.getMemberKeys().size() == 1 && exports.hasMember("default")) {
        exports = exports.getMember("default");
      }
      return exports;
    } finally {
      this.langContext.leave();
    }
  }

  @Override
  public Language getLang() {
    return Language.JAVASCRIPT;
  }
}
