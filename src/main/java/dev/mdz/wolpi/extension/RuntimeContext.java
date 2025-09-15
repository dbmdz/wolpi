package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.extension.model.Language;
import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/// Runtime context of an extension, contains all the state that is bound to the lifetime of a
/// single request.
public final class RuntimeContext {

  private final Language lang;
  private final Context langContext;
  private final Value extensionObject;

  /// Secure access to the Polyglot context, preventing multiple threads from using it at the
  /// same time. Uses a `ReentrantLock` to allow the same thread to acquire multiple leases on the
  /// context, while still preventing access from multiple threads at the same time.
  private final ReentrantLock contextLock = new ReentrantLock();

  /// @param lang            The GraalVM Polyglot language the extension is implemented in
  /// @param langContext     The GraalVM Polyglot context the extension runs in
  /// @param extensionObject The main extension object, containing the extension hooks as members
  public RuntimeContext(Language lang, Context langContext, Value extensionObject) {
    this.lang = lang;
    this.langContext = langContext;
    this.extensionObject = extensionObject;
  }

  /// Obtain a lease on the context to run code in it.
  ///
  /// Within the same thread, you can obtain multiple leases, but there can only ever be a single
  /// thread with leases at any given time, all others must wait for the context to be released by
  /// the thread currently holding leases on it.
  public ContextLease enter() {
    return new ContextLease(this);
  }

  /// Closes the associated Polyglot context, freeing up all resources associated with it.
  public void close() {
    langContext.close();
  }

  /// Clean up any request state by calling the `cleanup` hook on the extension object, if it
  // exists.
  public void cleanupAfterRequest() {
    try (var lease = enter()) {
      var cleanupFn = PolyglotHelpers.getDictOrObjectMember("cleanup", lease.extension());
      if (cleanupFn != null && cleanupFn.canExecute()) {
        cleanupFn.executeVoid();
      }
    }
  }

  /// @return The programming language the extension is implemented in
  public Language getLang() {
    return lang;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj == null || obj.getClass() != this.getClass()) {
      return false;
    }
    var that = (RuntimeContext) obj;
    return Objects.equals(this.lang, that.lang)
        && Objects.equals(this.langContext, that.langContext)
        && Objects.equals(this.extensionObject, that.extensionObject);
  }

  @Override
  public int hashCode() {
    return Objects.hash(lang, langContext, extensionObject);
  }

  @Override
  public String toString() {
    return "RuntimeContext["
        + "lang="
        + lang
        + ", "
        + "langContext="
        + langContext
        + ", "
        + "extensionObject="
        + extensionObject
        + ']';
  }

  /// Run a function within a lease on this context, automatically acquiring and releasing
  /// the lease.
  public <T> T run(Function<Value, T> action) {
    try (var lease = enter()) {
      return action.apply(lease.extension());
    }
  }

  /// Represents a lease on a [RuntimeContext], obtained by calling [RuntimeContext#enter].
  ///
  /// Use [#extension()] to access the hooks/extension object of the extension associated with this
  /// [RuntimeContext].
  public static class ContextLease implements AutoCloseable {
    private final RuntimeContext context;

    public ContextLease(RuntimeContext context) {
      this.context = context;
      this.context.contextLock.lock();
      this.context.langContext.enter();
    }

    /// @return The Polyglot [Value] that has the extension hooks as members
    public Value extension() {
      return context.extensionObject;
    }

    @Override
    public void close() {
      this.context.langContext.leave();
      this.context.contextLock.unlock();
    }
  }
}
