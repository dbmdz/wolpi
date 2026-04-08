package dev.mdz.wolpi.extension;

import com.google.common.util.concurrent.AtomicDouble;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter.Id;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/// Entry point to create metrics in extensions.
///
/// Calling the methods in this class will register the metrics in the provided [MeterRegistry].
/// This operation is cheap, the underlying library handles deduplication of metrics with the same
/// name and labels/tags.
///
/// We only expose the most common metric types: counters, gauges and timers. Histograms and
/// Distributions are currently out of scope due to their complexity and ease of misuse.
///
/// We do not expose the actual APIs from Micrometer directly, instead we wrap them in simple
/// proxies that only expose the necessary methods to manipulate the metrics.
public class ExtensionMetrics {
    private final MeterRegistry registry;
    private final Map<Id, GaugeMetric> knownGauges = new ConcurrentHashMap<>();

    public ExtensionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // NOTE: Unfortunately micrometer does not have a common Metric.Builder interface, i.e. the
    // different metric types all have their own builder classes. Therefore we need to duplicate
    // the logic for setting common properties like description and tags for each metric type.

    /// Creates a counter metric with the given name.
    public CounterMetric counter(String name) {
        return counter(name, null, null, null);
    }

    /// Creates a counter metric.
    ///
    /// @param name        The name of the metric.
    /// @param unit        The unit of the metric, or null for none.
    /// @param description The description of the metric, or null for none.
    /// @param labels      The labels/tags of the metric, or null for none.
    /// @return The created counter metric.
    public CounterMetric counter(
            String name, @Nullable String unit, @Nullable String description, @Nullable Map<String, String> labels) {
        var builder = Counter.builder(name);
        if (unit != null) {
            builder.baseUnit(unit);
        }
        if (description != null) {
            builder.description(description);
        }
        if (labels != null) {
            builder.tags(labels.entrySet().stream()
                    .map(e -> Tag.of(e.getKey(), e.getValue()))
                    .toList());
        }
        return new CounterMetric(builder.register(registry));
    }

    /// Creates a gauge metric with the given name.
    public GaugeMetric gauge(String name) {
        return gauge(name, null, null, null);
    }

    /// Creates a gauge metric.
    ///
    /// @param name        The name of the metric.
    /// @param unit        The unit of the metric, or null for none.
    /// @param description The description of the metric, or null for none.
    /// @param labels      The labels/tags of the metric, or null for none.
    /// @return The created gauge metric.
    public GaugeMetric gauge(
            String name, @Nullable String unit, @Nullable String description, @Nullable Map<String, String> labels) {
        // Gauges are a bit special, since we need to cache our own wrapper around them to
        // avoid creating multiple instances for the same underlying gauge. We use the meter ID
        // as the key for that. To achieve this, we create the gauge first, then check if we
        // already have a wrapper for it, and if not, create one and store it.
        final AtomicDouble value = new AtomicDouble(0.0d);
        var builder = Gauge.builder(name, value::get);
        if (unit != null) {
            builder.baseUnit(unit);
        }
        if (description != null) {
            builder.description(description);
        }
        if (labels != null) {
            builder.tags(labels.entrySet().stream()
                    .map(e -> Tag.of(e.getKey(), e.getValue()))
                    .toList());
        }
        var gauge = builder.register(registry);
        if (!knownGauges.containsKey(gauge.getId())) {
            knownGauges.put(gauge.getId(), new GaugeMetric(value));
        }
        return knownGauges.get(gauge.getId());
    }

    /// Creates a timer metric with the given name.
    public TimerMetric timer(String name) {
        return timer(name, null, null);
    }

    /// Creates a timer metric.
    ///
    /// @param name        The name of the metric.
    /// @param description The description of the metric, or null for none.
    /// @param labels      The labels/tags of the metric, or null for none.
    /// @return The created timer metric.
    public TimerMetric timer(String name, @Nullable String description, @Nullable Map<String, String> labels) {
        var builder = Timer.builder(name);
        if (description != null) {
            builder.description(description);
        }
        if (labels != null) {
            builder.tags(labels.entrySet().stream()
                    .map(e -> Tag.of(e.getKey(), e.getValue()))
                    .toList());
        }
        return new TimerMetric(builder.register(registry));
    }

    /// A metric to count things, should only go up
    public static class CounterMetric {
        private final Counter counter;

        protected CounterMetric(Counter counter) {
            this.counter = counter;
        }

        /// Increments the metric by 1.
        public void increment() {
            counter.increment();
        }

        /// Increments the metric by the given amount, should be positive.
        public void increment(double amount) {
            counter.increment(amount);
        }
    }

    /// A metric to observe values that can go up and down.
    public static class GaugeMetric {
        private final AtomicDouble value;

        protected GaugeMetric(AtomicDouble value) {
            this.value = value;
        }

        /// Sets the gauge to the given value.
        public void set(double newValue) {
            value.set(newValue);
        }
    }

    /// A metric to measure durations of operations.
    public static class TimerMetric {
        private final Timer timer;

        protected TimerMetric(Timer timer) {
            this.timer = timer;
        }

        /// Run a callable, record the duration it took to complete and return its result.
        public <T> T record(Callable<T> callable) throws Exception {
            return timer.recordCallable(callable);
        }

        /// Starts a timer that can be stopped later.
        public RunningTimer start() {
            Timer.Sample sample = Timer.start();
            return new RunningTimer(sample, timer);
        }

        /// A running timer that can be stopped to record the duration.
        public static class RunningTimer {
            private final Timer.Sample sample;
            private final Timer timer;

            protected RunningTimer(Timer.Sample sample, Timer timer) {
                this.sample = sample;
                this.timer = timer;
            }

            /// Stops the timer and records the duration.
            public void stop() {
                sample.stop(timer);
            }
        }
    }
}
