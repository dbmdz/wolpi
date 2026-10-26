package dev.mdz.wolpi.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExtensionMetricsTest {
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final ExtensionMetrics metrics = new ExtensionMetrics(registry);

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void recordsCallableDurationAndReturnsItsResult() throws Exception {
        var result = metrics.timer("callable").record(() -> {
            clock.add(Duration.ofMillis(42));
            return "result";
        });

        assertThat(result).isEqualTo("result");
        var timer = registry.get("callable").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(42);
    }

    @Test
    void recordsDurationWhenCallableThrows() {
        var metric = metrics.timer("failure");
        var failure = new IllegalStateException("failed");

        assertThatThrownBy(() -> metric.record(() -> {
                    clock.add(Duration.ofMillis(42));
                    throw failure;
                }))
                .isSameAs(failure);

        var timer = registry.get("failure").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(42);
    }

    @Test
    void runningTimerRecordsElapsedTime() throws InterruptedException {
        var realRegistry = new SimpleMeterRegistry();
        try {
            var sample = new ExtensionMetrics(realRegistry).timer("sample").start();
            Thread.sleep(25);
            sample.stop();

            var timer = realRegistry.get("sample").timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(0).isLessThan(5_000);
        } finally {
            realRegistry.close();
        }
    }
}
