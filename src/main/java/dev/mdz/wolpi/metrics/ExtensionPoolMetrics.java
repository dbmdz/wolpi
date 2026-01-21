package dev.mdz.wolpi.metrics;

import dev.mdz.wolpi.extension.ExtensionRegistry;
import dev.mdz.wolpi.extension.RuntimeContext;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/// Component responsible for registering extension pool metrics with Micrometer.
/// Separated from the main Wolpi configuration class to avoid circular dependencies.
@Component
public class ExtensionPoolMetrics {

    private final MeterRegistry meterRegistry;
    private final ExtensionRegistry extensionRegistry;
    private final GenericKeyedObjectPool<LoadedExtension, RuntimeContext> extensionContextPool;

    public ExtensionPoolMetrics(
            MeterRegistry meterRegistry,
            ExtensionRegistry extensionRegistry,
            @Qualifier("contextPool") GenericKeyedObjectPool<LoadedExtension, RuntimeContext> extensionContextPool) {
        this.meterRegistry = meterRegistry;
        this.extensionRegistry = extensionRegistry;
        this.extensionContextPool = extensionContextPool;
    }

    /// Register gauges for extension pool metrics after the application is fully started.
    @EventListener(ApplicationReadyEvent.class)
    public void registerMetrics() {
        Gauge.builder(
                        "wolpi.extensions.loaded",
                        () -> extensionRegistry.getExtensions().size())
                .description("Number of loaded extensions")
                .register(meterRegistry);

        // Register pool metrics for each extension
        for (LoadedExtension ext : extensionRegistry.getExtensions()) {
            String extName = ext.extensionInfo().name();

            Gauge.builder("wolpi.extensions.pooled", () -> extensionContextPool.getNumActive(ext))
                    .tag("extension_name", extName)
                    .tag("state", "active")
                    .register(meterRegistry);

            Gauge.builder("wolpi.extensions.pooled", () -> extensionContextPool.getNumIdle(ext))
                    .tag("extension_name", extName)
                    .tag("state", "idle")
                    .register(meterRegistry);

            Gauge.builder(
                            "wolpi.extensions.pool_waiting",
                            () -> extensionContextPool.getNumWaitersByKey().get(ext))
                    .tag("extension_name", extName)
                    .register(meterRegistry);
        }
    }
}
