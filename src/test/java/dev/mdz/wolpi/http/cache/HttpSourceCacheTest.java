package dev.mdz.wolpi.http.cache;

import static dev.mdz.wolpi.http.cache.CacheFixture.read;
import static dev.mdz.wolpi.http.cache.CacheFixture.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mdz.wolpi.config.HttpSourceCacheConfig;
import dev.mdz.wolpi.exceptions.HttpStatusException;
import dev.mdz.wolpi.http.cache.CacheFixture.MutableClock;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;
import reactor.netty.http.HttpProtocol;

class HttpSourceCacheTest {
    private static final URI URI_IMAGE = URI.create("https://example.com/image");

    @TempDir
    Path directory;

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<HttpRequest> sent = java.util.Collections.synchronizedList(new ArrayList<>());
    private HttpClient client;
    private Function<HttpRequest, HttpResponse<InputStream>> responder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        client = mock(HttpClient.class);
        responder = request -> response(200, "body", Map.of("Content-Length", "4", "ETag", "W/\"v1\""));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(call -> {
                    HttpRequest request = call.getArgument(0);
                    requests.incrementAndGet();
                    sent.add(request);
                    return responder.apply(request);
                });
    }

    private HttpSourceCacheConfig config(long maxSize, Duration freshness, long maxResourceSize) {
        return new HttpSourceCacheConfig(
                true,
                directory.resolve("cache"),
                DataSize.ofBytes(maxSize),
                freshness,
                DataSize.ofBytes(maxResourceSize),
                true);
    }

    private HttpSourceCache cache() {
        return configuredCache(config(128, Duration.ofSeconds(60), 128));
    }

    private HttpSourceCache configuredCache(HttpSourceCacheConfig config) {
        return new HttpSourceCache(config, directory, client, clock, Duration.ofSeconds(30));
    }

    @Test
    void completeResponsesHitDiskWithoutRangeRequests() throws Exception {
        responder = request -> response(200, "body", Map.of("Content-Length", "4", "Content-Encoding", "identity"));
        try (var cache = cache()) {
            try (var first = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(first.cacheStatusHeaderValue()).isEqualTo("miss");
                assertThat(read(first)).isEqualTo("body");
            }
            try (var hit = cache.acquire(URI_IMAGE, Map.of("Accept-Encoding", "identity"), null)) {
                assertThat(hit.cacheStatusHeaderValue()).isEqualTo("disk-hit");
                assertThat(hit.path()).isNotNull();
                assertThat(read(hit)).isEqualTo("body");
            }
            assertThat(requests).hasValue(1);
            assertThat(sent.getFirst().headers().firstValue("Range")).isEmpty();
            assertThat(sent.getFirst().headers().firstValue("Accept-Encoding")).contains("identity");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "true,Accept-Encoding,500",
        "false,Accept-Encoding,500",
        "true,Content-Encoding,502",
        "false,Content-Encoding,502"
    })
    void rejectsNonIdentityEncoding(boolean enabled, String header, int expectedStatus) throws Exception {
        InputStream body = mock(InputStream.class);
        boolean invalidRequest = header.equals("Accept-Encoding");
        responder = request -> response(
                200, body, Map.of("Content-Length", "4", "Content-Encoding", invalidRequest ? "identity" : "gzip"));
        var settings = new HttpSourceCacheConfig(
                enabled,
                directory.resolve("cache"),
                DataSize.ofBytes(128),
                Duration.ofSeconds(60),
                DataSize.ofBytes(128),
                true);
        try (var cache = configuredCache(settings)) {
            assertThatThrownBy(() -> cache.acquire(URI_IMAGE, invalidRequest ? Map.of(header, "gzip") : null, null))
                    .isInstanceOf(HttpStatusException.class)
                    .hasMessageContaining(header)
                    .extracting("httpStatusCode")
                    .isEqualTo(expectedStatus);
            assertThat(requests).hasValue(invalidRequest ? 0 : 1);
            if (!invalidRequest) verify(body).close();
            verifyNoMoreInteractions(body);
            assertThat(cache.tryAcquireFresh(URI_IMAGE, null, null)).isNull();
        }
    }

    @Test
    void encoded304DoesNotRefreshTheStoredIdentityBody() throws Exception {
        try (var cache = cache()) {
            try (var warm = cache.acquire(URI_IMAGE, null, null)) {}
            clock.advance(61);
            InputStream body = mock(InputStream.class);
            responder = request -> response(304, body, Map.of("Content-Encoding", "gzip"));
            assertThatThrownBy(() -> cache.acquire(URI_IMAGE, null, null))
                    .isInstanceOf(HttpStatusException.class)
                    .hasMessageContaining("Content-Encoding")
                    .extracting("httpStatusCode")
                    .isEqualTo(502);
            verify(body).close();
            verifyNoMoreInteractions(body);
            assertThat(cache.tryAcquireFresh(URI_IMAGE, null, null)).isNull();
            responder = request -> response(304, "", Map.of());
            try (var refreshed = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(refreshed.cacheStatusHeaderValue()).isEqualTo("revalidated");
                assertThat(read(refreshed)).isEqualTo("body");
            }
        }
    }

    @Test
    void expirationRevalidatesWeakEtagAndRetainsCompleteBody() throws Exception {
        try (var cache = cache()) {
            try (var first = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(read(first)).isEqualTo("body");
            }
            clock.advance(31);
            try (var hit = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(hit.cacheStatusHeaderValue()).isEqualTo("disk-hit");
            }
            clock.advance(30); // The hit must not extend the original freshness deadline.
            responder = request -> {
                assertThat(request.headers().firstValue("If-None-Match")).contains("W/\"v1\"");
                return response(304, "", Map.of("Cache-Control", "max-age=600", "Content-Length", "0"));
            };
            try (var refreshed = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(refreshed.cacheStatusHeaderValue()).isEqualTo("revalidated");
                assertThat(read(refreshed)).isEqualTo("body");
                assertThat(refreshed.responseHeaders().getContentLength()).isEqualTo(4);
            }
            try (var hit = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(hit.cacheStatusHeaderValue()).isEqualTo("disk-hit");
            }
            assertThat(requests).hasValue(2);
        }
    }

    @Test
    void contradictory304RefetchesRatherThanRelabelingOldBytes() throws Exception {
        try (var cache = cache()) {
            try (var first = cache.acquire(URI_IMAGE, null, null)) {}
            clock.advance(61);
            responder = request -> request.headers().firstValue("If-None-Match").isPresent()
                    ? response(304, "", Map.of("ETag", "\"different\""))
                    : response(200, "new!", Map.of("Content-Length", "4", "ETag", "\"different\""));
            try (var updated = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(updated.cacheStatusHeaderValue()).isEqualTo("miss");
                assertThat(read(updated)).isEqualTo("new!");
            }
            assertThat(requests).hasValue(3);
        }
    }

    @Test
    void diagnosticsCanBeSuppressedOnUpstreamAndCached304Responses() throws Exception {
        var defaults = config(128, Duration.ofSeconds(60), 128);
        var config = new HttpSourceCacheConfig(
                true,
                defaults.directory(),
                defaults.maxSize(),
                defaults.maxFreshness(),
                defaults.maxResourceSize(),
                false);
        try (var cache = configuredCache(config)) {
            try (var first = cache.acquire(URI_IMAGE, null, null)) {}
            try (var cached = cache.acquire(URI_IMAGE, Map.of("If-None-Match", "W/\"v1\""), null)) {
                assertThat(cached.responseHeaders().containsHeader(HttpSourceCache.RESPONSE_HEADER))
                        .isFalse();
                assertThat(read(cached)).isEqualTo("body");
            }
            responder = request -> response(304, "", Map.of(HttpSourceCache.RESPONSE_HEADER, "forged"));
            assertThatThrownBy(() -> cache.acquire(URI.create("https://example.com/other"), null, null))
                    .isInstanceOf(HttpStatusException.class)
                    .satisfies(error -> {
                        var status = (HttpStatusException) error;
                        assertThat(status.responseHeaders().containsHeader(HttpSourceCache.RESPONSE_HEADER))
                                .isFalse();
                    });
        }
    }

    @Test
    void failedContradictory304CleanupCannotStrandAPin() throws Exception {
        try (var cache = configuredCache(config(4, Duration.ofSeconds(30), 128))) {
            try (var warm = cache.acquire(URI_IMAGE, null, null)) {}
            clock.advance(61);
            InputStream body = new ByteArrayInputStream(new byte[0]) {
                @Override
                public void close() throws IOException {
                    throw new IOException("close failed");
                }
            };
            responder = request -> response(304, body, Map.of("ETag", "\"different\""));
            assertThatThrownBy(() -> cache.acquire(URI_IMAGE, null, null)).isInstanceOf(IOException.class);
            responder = request -> response(200, "body", Map.of("Content-Length", "4"));
            try (var other = cache.acquire(URI.create("https://example.com/other"), null, null)) {
                assertThat(other.cacheStatusHeaderValue()).isEqualTo("miss");
            }
        }
    }

    @Test
    void lastModifiedRevalidatesWhenNoEtagExists() throws Exception {
        String modified = "Tue, 01 Jan 2019 00:00:00 GMT";
        responder = request -> response(200, "body", Map.of("Content-Length", "4", "Last-Modified", modified));
        try (var cache = cache()) {
            try (var first = cache.acquire(URI_IMAGE, null, null)) {}
            clock.advance(61);
            responder = request -> {
                assertThat(request.headers().firstValue("If-Modified-Since")).contains(modified);
                return response(304, "", Map.of());
            };
            try (var hit = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(read(hit)).isEqualTo("body");
            }
        }
    }

    @Test
    void changedSourceReplacesEntryWhileOldDiskReaderFinishes() throws Exception {
        try (var cache = cache();
                var old = cache.acquire(URI_IMAGE, null, null)) {
            Path oldPath = old.path();
            clock.advance(61);
            responder = request -> response(200, "new!", Map.of("Content-Length", "4", "ETag", "\"v2\""));
            try (var updated = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(read(updated)).isEqualTo("new!");
                assertThat(read(old)).isEqualTo("body");
                assertThat(Files.exists(oldPath)).isTrue();
            }
            old.close();
            assertThat(Files.exists(oldPath)).isFalse();
            try (var hit = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(read(hit)).isEqualTo("new!");
            }
        }
    }

    @Test
    void originNoStoreStreamsWithoutRetainingTheBody() throws Exception {
        responder = request -> response(200, "body", Map.of("Content-Length", "4", "Cache-Control", "no-store"));
        try (var cache = cache()) {
            for (int i = 0; i < 2; i++) {
                try (var lease = cache.acquire(URI_IMAGE, null, null)) {
                    assertThat(lease.cacheStatusHeaderValue()).isEqualTo("bypass=no-store");
                    assertThat(read(lease)).isEqualTo("body");
                }
            }
            assertThat(requests).hasValue(2);
        }
    }

    @Test
    void noStoreOnRevalidationInvalidatesEntry() throws Exception {
        try (var cache = cache()) {
            try (var first = cache.acquire(URI_IMAGE, null, null)) {}
            clock.advance(61);
            responder = request -> response(304, "", Map.of("Cache-Control", "no-store"));
            try (var refreshed = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(refreshed.cacheStatusHeaderValue()).isEqualTo("bypass=no-store");
                assertThat(read(refreshed)).isEqualTo("body");
            }
            assertThat(cache.tryAcquireFresh(URI_IMAGE, null, null)).isNull();
            responder = request -> response(200, "new!", Map.of("Content-Length", "4"));
            try (var fresh = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(fresh.cacheStatusHeaderValue()).isEqualTo("miss");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"129,128,resource-too-large", "4,3,cache-too-small"})
    void oversizedBodiesBypassWithoutReadingAhead(long length, long capacity, String reason) throws Exception {
        InputStream body = mock(InputStream.class);
        responder = request -> response(200, body, Map.of("Content-Length", Long.toString(length)));
        try (var cache = configuredCache(config(capacity, Duration.ofSeconds(60), 128));
                var lease = cache.acquire(URI_IMAGE, null, null)) {
            assertThat(lease.cacheStatusHeaderValue()).isEqualTo("bypass=" + reason);
            assertThat(lease.path()).isNull();
            org.mockito.Mockito.verifyNoInteractions(body);
        }
    }

    @Test
    void unknownLengthBypassesWithoutReadingBodyAhead() throws Exception {
        InputStream body = mock(InputStream.class);
        responder = request -> response(200, body, Map.of());
        try (var cache = cache();
                var lease = cache.acquire(URI_IMAGE, null, null)) {
            assertThat(lease.cacheStatusHeaderValue()).isEqualTo("bypass=unknown-length");
            org.mockito.Mockito.verifyNoInteractions(body);
        }
    }

    @Test
    void partialResponsesAreNeverCached() throws Exception {
        responder = request -> response(206, "body", Map.of("Content-Length", "4", "Content-Range", "bytes 0-3/20"));
        try (var cache = cache();
                var lease = cache.acquire(URI_IMAGE, null, null)) {
            assertThat(lease.cacheStatusHeaderValue()).isEqualTo("bypass=partial-response");
            assertThat(read(lease)).isEqualTo("body");
            assertThat(cache.tryAcquireFresh(URI_IMAGE, null, null)).isNull();
        }
    }

    @Test
    void disablesCachingWithoutCreatingDirectory() throws Exception {
        HttpSourceCacheConfig defaults = config(128, Duration.ofSeconds(30), 128);
        var config = new HttpSourceCacheConfig(
                false,
                defaults.directory(),
                defaults.maxSize(),
                defaults.maxFreshness(),
                defaults.maxResourceSize(),
                true);
        try (var cache = configuredCache(config);
                var lease = cache.acquire(URI_IMAGE, null, null)) {
            assertThat(lease.cacheStatusHeaderValue()).isEqualTo("bypass=disabled");
            assertThat(Files.exists(directory.resolve("cache"))).isFalse();
        }
    }

    @Test
    void truncatedDownloadsFailAsGatewayErrors() throws Exception {
        try (var cache = cache()) {
            responder = request -> response(200, "body", Map.of("Content-Length", "5"));
            assertThatThrownBy(() -> cache.acquire(URI_IMAGE, null, null))
                    .isInstanceOf(HttpStatusException.class)
                    .extracting("httpStatusCode")
                    .isEqualTo(502);
            assertThat(cache.tryAcquireFresh(URI_IMAGE, null, null)).isNull();
            responder = request -> response(200, "body", Map.of("Content-Length", "4"));
            try (var success = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(success.cacheStatusHeaderValue()).isEqualTo("miss");
            }
        }
    }

    @Test
    void failedPublicationReclaimsItsRenamedBodyAndReservation() throws Exception {
        try (var cache = configuredCache(config(4, Duration.ofSeconds(60), 4))) {
            HttpHeaders request = new HttpHeaders();
            request.set(HttpHeaders.ACCEPT_ENCODING, "identity");
            Path metadata = directory.resolve("cache").resolve(CacheKey.create(URI_IMAGE, request, null) + ".json");
            Files.createDirectory(metadata);
            Path blocker = Files.writeString(metadata.resolve("blocker"), "x");

            assertThatThrownBy(() -> cache.acquire(URI_IMAGE, null, null)).isInstanceOf(IOException.class);
            try (var files = Files.list(directory.resolve("cache"))) {
                assertThat(files.map(path -> path.getFileName().toString()).toList())
                        .noneMatch(name -> name.endsWith(".body") || name.endsWith(".part"));
            }
            Files.delete(blocker);
            Files.delete(metadata);
            // The next download needs the entire budget, including the failed reservation's bytes.
            try (var retry = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(retry.cacheStatusHeaderValue()).isEqualTo("miss");
                assertThat(read(retry)).isEqualTo("body");
            }
        }
    }

    @Test
    void failedMetadataRefreshIsReportedAsStorageError() throws Exception {
        try (var cache = cache()) {
            try (var first = cache.acquire(URI_IMAGE, null, null)) {}
            clock.advance(61);
            // A non-empty directory at the metadata path makes the atomic metadata move fail.
            Path metadata;
            try (var files = Files.list(directory.resolve("cache"))) {
                metadata = files.filter(path -> path.toString().endsWith(".json"))
                        .findFirst()
                        .orElseThrow();
            }
            Files.delete(metadata);
            Files.createDirectory(metadata);
            Files.writeString(metadata.resolve("blocker"), "x");
            responder = request -> response(304, "", Map.of("Cache-Control", "max-age=600"));
            try (var refreshed = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(refreshed.cacheStatusHeaderValue()).isEqualTo("bypass=storage-error");
                assertThat(read(refreshed)).isEqualTo("body");
            }
            assertThat(cache.tryAcquireFresh(URI_IMAGE, null, null)).isNull();
        }
    }

    @Test
    void differentCredentialsAndVaryValuesNeverShareBodies() throws Exception {
        responder = request ->
                response(200, "body", Map.of("Content-Length", "4", "Cache-Control", "public", "Vary", "Accept"));
        try (var cache = cache()) {
            for (Map<String, String> headers : List.of(
                    Map.of("Authorization", "one", "Accept", "image/jpeg"),
                    Map.of("Authorization", "two", "Accept", "image/jpeg"),
                    Map.of("Authorization", "one", "Accept", "image/png"))) {
                try (var lease = cache.acquire(URI_IMAGE, headers, null)) {
                    assertThat(lease.cacheStatusHeaderValue()).isEqualTo("miss");
                }
            }
            assertThat(requests).hasValue(3);
        }
    }

    @Test
    void authenticatedSourcesNeedExplicitStoragePermission() throws Exception {
        try (var cache = cache();
                var lease = cache.acquire(URI_IMAGE, Map.of("Authorization", "Bearer secret"), null)) {
            assertThat(lease.cacheStatusHeaderValue()).isEqualTo("bypass=authenticated");
        }
    }

    @Test
    void sourceRequestNoCacheForcesValidation() throws Exception {
        try (var cache = cache()) {
            try (var first = cache.acquire(URI_IMAGE, Map.of("Cache-Control", "no-cache"), null)) {}
            responder = request -> response(304, "", Map.of());
            try (var validated = cache.acquire(URI_IMAGE, Map.of("Cache-Control", "no-cache"), null)) {
                assertThat(validated.cacheStatusHeaderValue()).isEqualTo("revalidated");
            }
        }
    }

    @Test
    void originErrorsRetainBodiesForLaterValidation() throws Exception {
        try (var cache = cache()) {
            try (var first = cache.acquire(URI_IMAGE, null, null)) {}
            clock.advance(61);
            responder = request -> response(503, "", Map.of("Content-Encoding", "gzip"));
            assertThatThrownBy(() -> cache.acquire(URI_IMAGE, null, null))
                    .isInstanceOf(HttpStatusException.class)
                    .extracting("httpStatusCode")
                    .isEqualTo(503);
            responder = request -> {
                assertThat(request.headers().firstValue("If-None-Match")).contains("W/\"v1\"");
                return response(304, "", Map.of());
            };
            try (var recovered = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(recovered.cacheStatusHeaderValue()).isEqualTo("revalidated");
                assertThat(read(recovered)).isEqualTo("body");
            }
        }
    }

    @Test
    void sourceConditionsDoNotGenerateClient304AndCold304IsAnUpstreamError() throws Exception {
        try (var cache = cache()) {
            try (var first = cache.acquire(URI_IMAGE, null, null)) {}
            try (var hit = cache.acquire(URI_IMAGE, Map.of("If-None-Match", "\"v1\""), null)) {
                assertThat(read(hit)).isEqualTo("body");
            }
            assertThat(requests).hasValue(1);
            responder = request -> response(304, "", Map.of("ETag", "\"unknown\""));
            URI other = URI.create("https://example.com/other");
            assertThatThrownBy(() -> cache.acquire(other, Map.of("If-None-Match", "\"unknown\""), null))
                    .isInstanceOf(HttpStatusException.class)
                    .extracting("httpStatusCode")
                    .isEqualTo(502);
            assertThat(cache.tryAcquireFresh(other, null, null)).isNull();
        }
    }

    @Test
    void reservationFailureFallsBackToStreaming() throws Exception {
        try (var cache = cache()) {
            // Force the temporary-file reservation to fail while leaving the owned directory intact.
            try (var files = Files.list(directory.resolve("cache"))) {
                for (Path path : files.toList()) {
                    Files.delete(path);
                }
            }
            Files.delete(directory.resolve("cache"));
            Files.writeString(directory.resolve("cache"), "not a directory");
            InputStream body = mock(InputStream.class);
            responder = request -> response(200, body, Map.of("Content-Length", "4"));
            try (var lease = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(lease.cacheStatusHeaderValue()).isEqualTo("bypass=storage-error");
                assertThat(lease.path()).isNull();
                org.mockito.Mockito.verifyNoInteractions(body);
            }
        }
    }

    @Test
    void failingUpstreamCloseDoesNotPublishOrLeakDiskCapacity() throws Exception {
        var config = config(4, Duration.ofSeconds(30), 128);
        InputStream body = new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
        responder = request -> response(200, body, Map.of("Content-Length", "4"));
        try (var cache = configuredCache(config)) {
            assertThatThrownBy(() -> cache.acquire(URI_IMAGE, null, null))
                    .isInstanceOf(IOException.class)
                    .hasMessage("close failed");
            assertThat(cache.tryAcquireFresh(URI_IMAGE, null, null)).isNull();
            responder = request -> response(200, "body", Map.of("Content-Length", "4"));
            try (var success = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(success.cacheStatusHeaderValue()).isEqualTo("miss");
                assertThat(success.path()).isNotNull();
            }
        }
    }

    @Test
    void pinnedDiskLimitIsRespected() throws Exception {
        var config = config(4, Duration.ofSeconds(30), 128);
        try (var cache = configuredCache(config);
                var pinned = cache.acquire(URI_IMAGE, null, null)) {
            assertThat(pinned.path()).isNotNull();
            try (var other = cache.acquire(URI.create("https://example.com/other"), null, null)) {
                assertThat(other.cacheStatusHeaderValue()).isEqualTo("bypass=capacity");
            }
            assertThat(read(pinned)).isEqualTo("body");
            pinned.close();
            try (var other = cache.acquire(URI.create("https://example.com/other"), null, null)) {
                assertThat(other.cacheStatusHeaderValue()).isEqualTo("miss");
            }
        }
    }

    @Test
    void byteLimitEvictsLeastRecentlyUsedResponses() throws Exception {
        var config = config(8, Duration.ofSeconds(30), 128);
        URI second = URI.create("https://example.com/second");
        URI third = URI.create("https://example.com/third");
        try (var cache = configuredCache(config)) {
            try (var first = cache.acquire(URI_IMAGE, null, null)) {}
            try (var other = cache.acquire(second, null, null)) {}
            try (var first = cache.acquire(URI_IMAGE, null, null)) {}
            try (var other = cache.acquire(third, null, null)) {}
            try (var first = cache.acquire(URI_IMAGE, null, null)) {
                assertThat(first.cacheStatusHeaderValue()).isEqualTo("disk-hit");
            }
            try (var evicted = cache.acquire(second, null, null)) {
                assertThat(evicted.cacheStatusHeaderValue()).isEqualTo("miss");
            }
            assertThat(requests).hasValue(4);
        }
    }

    @Test
    void diskDirectoryHasExclusiveOwnershipAndRecoversOrphans() throws Exception {
        try (var first = cache()) {
            assertThatThrownBy(this::cache).isInstanceOf(IllegalStateException.class);
        }
        Files.writeString(directory.resolve("cache/.download-orphan.part"), "partial");
        Files.writeString(directory.resolve("cache/" + "a".repeat(64) + ".json"), "invalid");
        try (var recovered = cache()) {
            assertThat(Files.exists(directory.resolve("cache/.download-orphan.part")))
                    .isFalse();
            assertThat(Files.exists(directory.resolve("cache/" + "a".repeat(64) + ".json")))
                    .isFalse();
        }
    }

    @Test
    void corruptedBodyIsDiscardedBeforeRestartReuse() throws Exception {
        Path body;
        try (var cache = cache();
                var lease = cache.acquire(URI_IMAGE, null, null)) {
            body = lease.path();
        }
        Files.writeString(body, "broken body");
        try (var cache = cache();
                var recovered = cache.acquire(URI_IMAGE, null, null)) {
            assertThat(recovered.cacheStatusHeaderValue()).isEqualTo("miss");
            assertThat(read(recovered)).isEqualTo("body");
        }
        assertThat(requests).hasValue(2);
    }

    @Test
    void waitingForAnotherAcquisitionConsumesTheSameBudget() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        responder = request -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return response(200, "body", Map.of("Content-Length", "4"));
        };
        try (var cache = new HttpSourceCache(
                        config(128, Duration.ofMinutes(1), 128), directory, client, clock, Duration.ofMillis(150));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> cache.acquire(URI_IMAGE, null, null));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> cache.acquire(URI_IMAGE, null, null))
                        .isInstanceOf(java.net.http.HttpTimeoutException.class)
                        .hasMessageContaining("acquisition");
            } finally {
                release.countDown();
            }
            assertThatThrownBy(() -> owner.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(java.net.http.HttpTimeoutException.class);
            assertThat(requests).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @CsvSource({"HTTP11,empty", "HTTP11,trickle", "H2C,partial"})
    @Timeout(10)
    void stalledBodiesReleaseReservationsAndLeaveNoPartialFiles(HttpProtocol protocol, String mode) throws Exception {
        var slow = new AtomicBoolean(true);
        var server = reactor.netty.http.server.HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .protocol(
                        protocol == HttpProtocol.H2C
                                ? new HttpProtocol[] {HttpProtocol.HTTP11, HttpProtocol.H2C}
                                : new HttpProtocol[] {HttpProtocol.HTTP11})
                .handle((request, response) -> {
                    if (!request.uri().equals("/slow") || !slow.get()) {
                        return response.header("Content-Length", "4").sendString(Flux.just("body"));
                    }
                    Flux<io.netty.buffer.ByteBuf> bytes =
                            switch (mode) {
                                case "empty" -> Flux.never();
                                case "partial" ->
                                    Flux.concat(Flux.just(Unpooled.wrappedBuffer(new byte[] {'x'})), Flux.never());
                                default ->
                                    Flux.interval(Duration.ofMillis(50))
                                            .map(ignored -> Unpooled.wrappedBuffer(new byte[] {'x'}))
                                            .take(20);
                            };
                    return response.header("Content-Length", "20").send(bytes, ignored -> true);
                })
                .bindNow();
        Duration budget = Duration.ofMillis(300);
        // Deliberately use the stock client: only the cache closes timed-out bodies.
        try (var stock = HttpClient.newHttpClient();
                var cache =
                        new HttpSourceCache(config(20, Duration.ofMinutes(1), 128), directory, stock, clock, budget)) {
            URI base = URI.create("http://127.0.0.1:" + server.port());
            var probe = stock.send(
                    HttpRequest.newBuilder(base.resolve("/fast")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(probe.version())
                    .isEqualTo(protocol == HttpProtocol.H2C ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);
            assertThatThrownBy(() -> cache.acquire(base.resolve("/slow"), null, null))
                    .isInstanceOf(java.net.http.HttpTimeoutException.class);
            try (var files = Files.list(directory.resolve("cache"))) {
                assertThat(files.map(p -> p.getFileName().toString()).toList())
                        .noneMatch(name ->
                                name.startsWith(".download-") || name.endsWith(".body") || name.endsWith(".json"));
            }
            // Reusing the same key and the full disk budget verifies lock and reservation release.
            slow.set(false);
            try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
                var next = worker.submit(() -> {
                    try (var good = cache.acquire(base.resolve("/slow"), null, null)) {
                        assertThat(good.cacheStatusHeaderValue()).isEqualTo("miss");
                        return read(good);
                    }
                });
                assertThat(next.get(2, TimeUnit.SECONDS)).isEqualTo("body");
            }
        } finally {
            server.disposeNow();
        }
    }

    @Test
    @Timeout(5)
    void deadlineClosureReturningEofIsStillATimeout() throws Exception {
        var closed = new CountDownLatch(1);
        InputStream body = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    closed.await();
                    return -1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        responder = request -> response(200, body, Map.of("Content-Length", "4"));
        try (var cache = new HttpSourceCache(
                config(4, Duration.ofMinutes(1), 128), directory, client, clock, Duration.ofMillis(150))) {
            assertThatThrownBy(() -> cache.acquire(URI_IMAGE, null, null))
                    .isInstanceOf(java.net.http.HttpTimeoutException.class);
            assertThat(cache.tryAcquireFresh(URI_IMAGE, null, null)).isNull();
        }
    }
}
