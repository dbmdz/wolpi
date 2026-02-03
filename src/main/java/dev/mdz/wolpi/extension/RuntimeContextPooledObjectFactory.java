package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.extension.model.JSLoadedExtension;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.extension.model.PythonLoadedExtension;
import dev.mdz.wolpi.metrics.ExtensionPoolMetrics;
import dev.mdz.wolpi.metrics.ExtensionPoolMetrics.PoolEvent;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.DestroyMode;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.jspecify.annotations.Nullable;

/// Factory method to create and manage [RuntimeContext] objects for the context pool.
///
/// Cleans up the request state by calling the "cleanup" hook for an extension when the context
/// is returned to the pool, and closes the GraalVM Polyglot context when the context is destroyed.
public class RuntimeContextPooledObjectFactory extends BaseKeyedPooledObjectFactory<LoadedExtension, RuntimeContext> {

    private final GraalContextSupplier contextSupplier;
    private final AtomicReference<@Nullable ExtensionPoolMetrics> poolMetrics = new AtomicReference<>();

    public RuntimeContextPooledObjectFactory(GraalContextSupplier contextSupplier) {
        this.contextSupplier = contextSupplier;
    }

    /// Set the metrics collector. Called by ExtensionPoolMetrics after it's been constructed
    /// to avoid circular dependency issues.
    public void setPoolMetrics(ExtensionPoolMetrics metrics) {
        this.poolMetrics.set(metrics);
    }

    /// Uses the callback on the [LoadedExtension] to create the [RuntimeContext] and run
    /// the `setup` hook.
    @Override
    public RuntimeContext create(LoadedExtension ext) throws Exception {
        var ctx =
                switch (ext) {
                    case JSLoadedExtension jsExt ->
                        new JSRuntimeContext(jsExt.source(), jsExt.guestContext(), contextSupplier);
                    case PythonLoadedExtension pyExt ->
                        new PythonRuntimeContext(
                                pyExt.source(),
                                pyExt.entryPoint(),
                                pyExt.virtualEnvironment(),
                                pyExt.guestContext(),
                                contextSupplier);
                };
        ctx.setup();
        recordEvent(ext, PoolEvent.CREATED);

        return ctx;
    }

    @Override
    public PooledObject<RuntimeContext> wrap(RuntimeContext runtimeContext) {
        return new DefaultPooledObject<>(runtimeContext);
    }

    /// Called when a context is borrowed from the pool, effectively NOP, we just record the event
    /// for the metrics.
    @Override
    public void activateObject(LoadedExtension key, PooledObject<RuntimeContext> p) {
        recordEvent(key, PoolEvent.BORROWED);
    }

    /// Clears the request state when the context is returned to the pool, by calling the `cleanup`
    /// hook if it exists.
    @Override
    public void passivateObject(LoadedExtension key, PooledObject<RuntimeContext> p) {
        p.getObject().cleanupAfterRequest();
        recordEvent(key, PoolEvent.RETURNED);
    }

    /// Closes the GraalVM Polyglot [org.graalvm.polyglot.Context] to free up resources when the object is destroyed.
    @Override
    public void destroyObject(LoadedExtension key, PooledObject<RuntimeContext> p, DestroyMode destroyMode) {
        p.getObject().close();
        recordEvent(key, PoolEvent.DESTROYED);
    }

    private void recordEvent(LoadedExtension key, PoolEvent event) {
        var metrics = poolMetrics.get();
        if (metrics != null) {
            metrics.recordEvent(event, key.extensionInfo().name());
        }
    }
}
