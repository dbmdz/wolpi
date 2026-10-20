package dev.mdz.wolpi.http.cache;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// One monotonic budget for acquisition waits, requests, retries, and cache downloads.
final class AcquisitionDeadline {
    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// Shared thread pool for the scheduled closer threads
    private static final ScheduledThreadPoolExecutor closerExecutor = new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("http-source-cache-timeouts").factory());

    static {
        closerExecutor.setRemoveOnCancelPolicy(true);
    }

    private final long started = System.nanoTime();
    private final long budget;

    AcquisitionDeadline(Duration timeout) {
        validate(timeout);
        budget = timeout.toNanos();
    }

    /// Ensure that the configured timeout matches our criteria
    static void validate(@Nullable Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("HTTP source acquisition timeout must be positive");
        }
        timeout.toNanos(); // Reject durations that overflow the monotonic budget.
    }

    /// Get the remaining time in nanoseconds, throws if the time is less than zero
    long remainingNanos() throws HttpTimeoutException {
        long remaining = budget - (System.nanoTime() - started);
        if (remaining <= 0) {
            throw new HttpTimeoutException("HTTP source acquisition deadline exceeded");
        }
        return remaining;
    }

    /// Check how much time is remaining on the deadline and throw if none is left
    Duration remaining() throws HttpTimeoutException {
        return Duration.ofNanos(remainingNanos());
    }

    void check() throws HttpTimeoutException {
        remainingNanos();
    }

    /// Closing the JDK response stream cancels its subscription and wakes blocked readers.
    /// Only cache-owned downloads use this; bypassed streams keep the stock client semantics.
    ScheduledFuture<?> closeOnExpiry(InputStream body) throws HttpTimeoutException {
        return closerExecutor.schedule(
                () -> {
                    try {
                        body.close();
                    } catch (IOException e) {
                        log.debug("Could not close expired HTTP source download", e);
                    }
                },
                remainingNanos(),
                TimeUnit.NANOSECONDS);
    }
}
