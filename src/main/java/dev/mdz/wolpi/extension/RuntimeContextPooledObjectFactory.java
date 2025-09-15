package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.extension.model.LoadedExtension;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.DestroyMode;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.graalvm.polyglot.Context;

/// Factory method to create and manage [RuntimeContext] objects for the context pool.
///
/// Cleans up the request state by calling the "cleanup" hook for an extension when the context
/// is returned to the pool, and closes the GraalVM Polyglot context when the context is destroyed.
public class RuntimeContextPooledObjectFactory
    extends BaseKeyedPooledObjectFactory<LoadedExtension, RuntimeContext> {

  /// Uses the callback on the [LoadedExtension] to create the [RuntimeContext].
  @Override
  public RuntimeContext create(LoadedExtension loadedExtension) throws Exception {
    return loadedExtension.createRuntimeContext();
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

  /// Closes the GraalVM Polyglot [Context] to free up resources when the object is destroyed.
  @Override
  public void destroyObject(
      LoadedExtension key, PooledObject<RuntimeContext> p, DestroyMode destroyMode) {
    p.getObject().close();
  }
}
