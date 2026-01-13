package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.extension.model.JSLoadedExtension;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.extension.model.PythonLoadedExtension;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.DestroyMode;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

/// Factory method to create and manage [RuntimeContext] objects for the context pool.
///
/// Cleans up the request state by calling the "cleanup" hook for an extension when the context
/// is returned to the pool, and closes the GraalVM Polyglot context when the context is destroyed.
public class RuntimeContextPooledObjectFactory extends BaseKeyedPooledObjectFactory<LoadedExtension, RuntimeContext> {

    private final GraalContextSupplier contextSupplier;

    public RuntimeContextPooledObjectFactory(GraalContextSupplier contextSupplier) {
        this.contextSupplier = contextSupplier;
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
        return ctx;
    }

    @Override
    public PooledObject<RuntimeContext> wrap(RuntimeContext runtimeContext) {
        return new DefaultPooledObject<>(runtimeContext);
    }

    /// Clears the request state when the context is returned to the pool, by calling the `cleanup`
    /// hook if it exists.
    @Override
    public void passivateObject(LoadedExtension key, PooledObject<RuntimeContext> p) {
        p.getObject().cleanupAfterRequest();
    }

    /// Closes the GraalVM Polyglot [org.graalvm.polyglot.Context] to free up resources when the object is destroyed.
    @Override
    public void destroyObject(LoadedExtension key, PooledObject<RuntimeContext> p, DestroyMode destroyMode) {
        p.getObject().close();
    }
}
