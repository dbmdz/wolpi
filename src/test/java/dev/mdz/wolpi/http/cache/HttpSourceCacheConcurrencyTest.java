package dev.mdz.wolpi.http.cache;

import static dev.mdz.wolpi.http.cache.CacheFixture.IMAGE;
import static dev.mdz.wolpi.http.cache.CacheFixture.await;
import static dev.mdz.wolpi.http.cache.CacheFixture.read;
import static dev.mdz.wolpi.http.cache.CacheFixture.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mdz.wolpi.exceptions.HttpStatusException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class HttpSourceCacheConcurrencyTest {
    @TempDir
    Path directory;

    private static void awaitAcquisitionWait(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // The leader is blocked in the responder; the waiter must reach the timed per-key lock.
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            assertThat(thread.isAlive()).isTrue();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"miss", "revalidated", "stale"})
    void overlappingAcquisitionsShareOutcome(String outcome) throws Exception {
        var fixture = new CacheFixture(directory);
        boolean warm = !outcome.equals("miss");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fixture.responder = request -> {
            if (fixture.requests.get() == 1) {
                if (!warm) {
                    entered.countDown();
                    await(release);
                }
                return response(
                        200,
                        "body",
                        Map.of(
                                "Content-Length",
                                "4",
                                "Cache-Control",
                                outcome.equals("stale") ? "max-age=0" : "no-cache",
                                "ETag",
                                "\"v1\""));
            }
            assertThat(request.headers().firstValue("If-None-Match")).contains("\"v1\"");
            entered.countDown();
            await(release);
            return response(outcome.equals("stale") ? 503 : 304, "", Map.of());
        };
        try (var cache = fixture.cache();
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                if (warm) {
                    try (var initial = cache.acquire(IMAGE, null, null)) {}
                }
                var leader = workers.submit(() -> {
                    try (var lease = cache.acquire(IMAGE, null, null)) {
                        assertThat(lease.cacheStatusHeaderValue()).isEqualTo(outcome);
                        return read(lease);
                    }
                });
                await(entered);
                var waiting = new FutureTask<>(() -> {
                    try (var lease = cache.acquire(IMAGE, null, null)) {
                        assertThat(lease.cacheStatusHeaderValue()).isEqualTo(outcome);
                        return read(lease);
                    }
                });
                Thread waiter = Thread.ofVirtual().start(waiting);
                try {
                    awaitAcquisitionWait(waiter);
                    release.countDown();
                    assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo("body");
                    assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("body");
                    assertThat(fixture.requests).hasValue(warm ? 2 : 1);
                    // Coalescing must not skip validation for a later, independent request.
                    try (var next = cache.acquire(IMAGE, null, null)) {
                        assertThat(read(next)).isEqualTo("body");
                    }
                    assertThat(fixture.requests).hasValue(warm ? 3 : 2);
                } finally {
                    release.countDown();
                    waiting.cancel(true);
                    waiter.join(5000);
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void waitingCallerCanRetryAfterTheLeaderFails() throws Exception {
        var fixture = new CacheFixture(directory);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fixture.responder = request -> {
            if (fixture.requests.get() == 1) {
                entered.countDown();
                await(release);
                return response(503, "", Map.of());
            }
            return response(200, "body", Map.of("Content-Length", "4"));
        };
        try (var cache = fixture.cache();
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var leader = workers.submit(() -> {
                    try (var lease = cache.acquire(IMAGE, null, null)) {
                        return read(lease);
                    }
                });
                await(entered);
                var waiting = new FutureTask<>(() -> {
                    try (var lease = cache.acquire(IMAGE, null, null)) {
                        return read(lease);
                    }
                });
                Thread waiter = Thread.ofVirtual().start(waiting);
                try {
                    awaitAcquisitionWait(waiter);
                    release.countDown();
                    assertThatThrownBy(() -> leader.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(HttpStatusException.class);
                    assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("body");
                    try (var hit = cache.acquire(IMAGE, null, null)) {
                        assertThat(hit.cacheStatusHeaderValue()).isEqualTo("disk-hit");
                    }
                    assertThat(fixture.requests).hasValue(2);
                } finally {
                    release.countDown();
                    waiting.cancel(true);
                    waiter.join(5000);
                }
            } finally {
                release.countDown();
            }
        }
    }
}
