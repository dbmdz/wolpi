package dev.mdz.wolpi.metrics;

import dev.mdz.wolpi.extension.ExtensionRegistry;
import dev.mdz.wolpi.extension.RuntimeContext;
import dev.mdz.wolpi.extension.RuntimeContextPooledObjectFactory;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConcurrentHashMap<PoolEventKey, Counter> eventCounters = new ConcurrentHashMap<>();

    public ExtensionPoolMetrics(
            MeterRegistry meterRegistry,
            ExtensionRegistry extensionRegistry,
            @Qualifier("contextPool") GenericKeyedObjectPool<LoadedExtension, RuntimeContext> extensionContextPool) {
        this.meterRegistry = meterRegistry;
        this.extensionRegistry = extensionRegistry;
        this.extensionContextPool = extensionContextPool;

        // Wire ourselves into the factory to collect metrics
        if (extensionContextPool.getFactory() instanceof RuntimeContextPooledObjectFactory factory) {
            factory.setPoolMetrics(this);
        }
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

            Gauge.builder("wolpi.extensions.pool", () -> extensionContextPool.getNumActive(ext))
                    .tag("extension_name", extName)
                    .tag("state", "active")
                    .description("Number of active (borrowed) extension contexts")
                    .register(meterRegistry);

            Gauge.builder("wolpi.extensions.pool", () -> extensionContextPool.getNumIdle(ext))
                    .tag("extension_name", extName)
                    .tag("state", "idle")
                    .description("Number of idle extension contexts available in pool")
                    .register(meterRegistry);

            Gauge.builder(
                            "wolpi.extensions.pool",
                            () -> extensionContextPool.getNumWaitersByKey().get(ext))
                    .tag("extension_name", extName)
                    .tag("state", "client_waiting")
                    .description("Number of threads waiting for an extension context to become available")
                    .register(meterRegistry);

            // Register counters for pool lifecycle operations
            eventCounters.put(
                    new PoolEventKey(extName, PoolEvent.CREATED),
                    Counter.builder("wolpi.extensions.pool.events")
                            .tag("extension_name", extName)
                            .tag("event", "created")
                            .description("Total number of times an extension context was created")
                            .register(meterRegistry));

            eventCounters.put(
                    new PoolEventKey(extName, PoolEvent.DESTROYED),
                    Counter.builder("wolpi.extensions.pool.events")
                            .tag("extension_name", extName)
                            .tag("event", "destroyed")
                            .description("Total number of times an extension context was destroyed")
                            .register(meterRegistry));

            eventCounters.put(
                    new PoolEventKey(extName, PoolEvent.BORROWED),
                    Counter.builder("wolpi.extensions.pool.events")
                            .tag("extension_name", extName)
                            .tag("event", "borrowed")
                            .description("Total number of times an extension context was borrowed from pool")
                            .register(meterRegistry));

            eventCounters.put(
                    new PoolEventKey(extName, PoolEvent.RETURNED),
                    Counter.builder("wolpi.extensions.pool.events")
                            .tag("extension_name", extName)
                            .tag("event", "returned")
                            .description("Total number of times an extension context was returned to pool")
                            .register(meterRegistry));
        }
    }

    /// Record a [PoolEvent] that occurred for the given extension.
    ///
    /// @param event The type of event that occurred.
    /// @param extensionName The name of the extension for which the event occurred.
    public void recordEvent(PoolEvent event, String extensionName) {
        Counter counter = eventCounters.get(new PoolEventKey(extensionName, event));
        if (counter != null) {
            counter.increment();
        }
    }

    private record PoolEventKey(String extensionName, PoolEvent event) {}

    /// Types of pool events to track in the metrics
    public enum PoolEvent {
        CREATED,
        DESTROYED,
        BORROWED,
        RETURNED
    }
}
