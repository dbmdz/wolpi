package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.model.ExtensionHooks;
import dev.mdz.wolpi.extension.model.Language;
import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;

/// Runtime context of an extension, contains all the state that is bound to the lifetime of a
/// single request.
public abstract class RuntimeContext implements AutoCloseable {

    protected final Context langContext;
    protected final Value extensionObject;

    /// Secure access to the Polyglot context, preventing multiple threads from using it at the same
    /// time. Uses a `ReentrantLock` to allow the same thread to acquire multiple leases on the
    /// context, while still preventing access from multiple threads at the same time.
    private final ReentrantLock contextLock = new ReentrantLock();

    protected RuntimeContext(GraalContextSupplier contextSupplier) throws ExtensionLoadException {
        this.langContext = getGraalContext(contextSupplier);
        this.extensionObject = getExtensionObject();
    }

    /// Calls the `setup` hook on the extension object to perform any necessary setup for the
    /// extension. If the hook is not implemented, it is silently ignored.
    public void setup() {
        if (hasHook(ExtensionHooks.SETUP)) {
            runHook(ExtensionHooks.SETUP);
        }
    }

    /// @return The underlying GraalVM Polyglot context
    protected abstract Context getGraalContext(GraalContextSupplier contextSupplier) throws ExtensionLoadException;

    /// @return The Polyglot [Value] that has the extension hooks as members
    protected abstract Value getExtensionObject() throws ExtensionLoadException;

    /// @return The programming language the extension is implemented in
    public abstract Language getLang();

    boolean hasHook(ExtensionHooks hook) {
        return hook.getValidNames().stream()
                .flatMap(name ->
                        Optional.ofNullable(PolyglotHelpers.getDictOrObjectMember(name, extensionObject, true))
                                .stream())
                .anyMatch(Value::canExecute);
    }

    /// Convenience method to run a specific hook on the extension, passing the given arguments to
    /// it.
    ///
    /// @param hook The hook to run
    /// @param args The arguments to pass to the hook
    /// @return The result of the hook execution
    /// @throws IllegalStateException if the hook is not implemented in the extension
    public @Nullable Value runHook(ExtensionHooks hook, @Nullable Object... args) {
        return run(ext -> hook.getValidNames().stream()
                .flatMap(name -> Optional.ofNullable(PolyglotHelpers.getDictOrObjectMember(name, ext, true)).stream())
                .filter(Value::canExecute)
                .findFirst()
                .map(fn -> fn.execute(Arrays.stream(args)
                        .map(val -> PolyglotHelpers.toGuest(val, getLang()))
                        .toArray()))
                .orElseThrow(() -> new IllegalStateException("Hook " + hook + " not implemented in extension")));
    }

    /// Obtain a lease on the context to run code in it.
    ///
    /// Within the same thread, you can obtain multiple leases, but there can only ever be a single
    /// thread with leases at any given time, all others must wait for the context to be released by
    /// the thread currently holding leases on it.
    public ContextLease enter() {
        return new ContextLease(this);
    }

    /// Runs the destroy hook to clean up resources created by the extension and then closes the
    /// associated Polyglot context, freeing up all resources associated with it.
    @Override
    public void close() {
        if (hasHook(ExtensionHooks.DESTROY)) {
            runHook(ExtensionHooks.DESTROY);
        }
        langContext.close();
    }

    /// Clean up any request state by calling the `cleanup` hook on the extension object, if it
    /// exists.
    public void cleanupAfterRequest() {
        runHook(ExtensionHooks.CLEANUP);
    }

    @Override
    public String toString() {
        return "RuntimeContext[" + "langContext=" + langContext + ", " + "extensionObject=" + extensionObject + ']';
    }

    /// Run a function within a lease on this context, automatically acquiring and releasing the
    /// lease.
    public <T> T run(Function<Value, T> action) {
        try (var lease = enter()) {
            return action.apply(lease.extension());
        }
    }

    /// Represents a lease on a [RuntimeContext], obtained by calling [RuntimeContext#enter].
    ///
    /// Use [#extension()] to access the hooks/extension object of the extension associated with
    /// this [RuntimeContext].
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
