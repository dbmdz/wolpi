package dev.mdz.wolpi.http.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mdz.wolpi.config.HttpSourceCacheConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.util.unit.DataSize;

final class CacheFixture {
    static final URI IMAGE = URI.create("https://example.com/image");

    final Path directory;
    final MutableClock clock = new MutableClock();
    final HttpClient client = mock(HttpClient.class);
    final AtomicInteger requests = new AtomicInteger();
    Responder responder = request -> response(200, "body", Map.of("Content-Length", "4", "ETag", "W/\"v1\""));

    @SuppressWarnings("unchecked")
    CacheFixture(Path directory) throws Exception {
        this.directory = directory;
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(call -> {
                    HttpRequest request = call.getArgument(0);
                    requests.incrementAndGet();
                    return responder.send(request);
                });
    }

    HttpSourceCache cache() {
        return cache(true);
    }

    HttpSourceCache cache(boolean enabled) {
        var config = new HttpSourceCacheConfig(
                enabled, directory, DataSize.ofBytes(128), Duration.ofSeconds(60), DataSize.ofBytes(128), true);
        return new HttpSourceCache(config, directory, client, clock, Duration.ofSeconds(5));
    }

    static String read(HttpSourceCache.Lease lease) throws IOException {
        try (var stream = lease.openStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static HttpResponse<InputStream> response(int status, String body, Map<String, String> headers) {
        return response(status, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), headers);
    }

    @SuppressWarnings("unchecked")
    static HttpResponse<InputStream> response(int status, InputStream body, Map<String, String> headers) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        Map<String, List<String>> values = new LinkedHashMap<>();
        headers.forEach((name, value) -> values.put(name, List.of(value)));
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(values, (name, value) -> true));
        return response;
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    interface Responder {
        HttpResponse<InputStream> send(HttpRequest request) throws Exception;
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
