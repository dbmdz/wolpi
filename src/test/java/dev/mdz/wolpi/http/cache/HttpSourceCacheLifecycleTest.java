package dev.mdz.wolpi.http.cache;

import static dev.mdz.wolpi.http.cache.CacheFixture.IMAGE;
import static dev.mdz.wolpi.http.cache.CacheFixture.await;
import static dev.mdz.wolpi.http.cache.CacheFixture.read;
import static dev.mdz.wolpi.http.cache.CacheFixture.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class HttpSourceCacheLifecycleTest {
    @TempDir
    Path directory;

    private CacheFixture fixture;

    @BeforeEach
    void setup() throws Exception {
        fixture = new CacheFixture(directory);
    }

    private void assertDirectoryOwned() {
        assertThatThrownBy(() -> fixture.cache())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initialize");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closeRejectsNewWorkEvenWhenCachingWasDisabled(boolean enabled) throws Exception {
        try (var cache = fixture.cache(enabled)) {
            cache.close();
            cache.close();
            assertThatThrownBy(() -> cache.tryAcquireFresh(IMAGE, null, null))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> cache.acquire(IMAGE, null, null)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(fixture.client);
        }
        try (var replacement = fixture.cache()) {
            assertThat(replacement.tryAcquireFresh(IMAGE, null, null)).isNull();
        }
    }

    @Test
    void closeRetainsOwnershipUntilFreshLookupLeaseCloses() throws Exception {
        try (var cache = fixture.cache()) {
            try (var warm = cache.acquire(IMAGE, null, null)) {}
            try (var pinned = cache.tryAcquireFresh(IMAGE, null, null)) {
                cache.close();
                assertDirectoryOwned();
                assertThat(read(pinned)).isEqualTo("body");
                pinned.close();
                pinned.close();
                assertThat(pinned.canReopenBody()).isFalse();
                try (var replacement = fixture.cache();
                        var hit = replacement.acquire(IMAGE, null, null)) {
                    assertThat(hit.cacheStatusHeaderValue()).isEqualTo("disk-hit");
                    assertThat(read(hit)).isEqualTo("body");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 304})
    void closeDuringRevalidationCannotHandFilesToANewOwner(int status) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var cache = fixture.cache();
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                try (var warm = cache.acquire(IMAGE, null, null)) {}
                fixture.clock.advance(61);
                fixture.responder = request -> {
                    entered.countDown();
                    await(release);
                    return response(status, status == 304 ? "" : "new!", Map.of("Content-Length", "4"));
                };
                var pending = workers.submit(() -> cache.acquire(IMAGE, null, null));
                await(entered);
                cache.close();
                assertDirectoryOwned();
                release.countDown();
                try (var acquired = pending.get(5, TimeUnit.SECONDS)) {
                    assertThat(read(acquired)).isEqualTo(status == 304 ? "body" : "new!");
                    assertDirectoryOwned();
                }
                // The replacement must be safe from any cleanup by the old instance.
                try (var replacement = fixture.cache();
                        var pinned = replacement.acquire(IMAGE, null, null)) {
                    assertThat(pinned.cacheStatusHeaderValue()).isEqualTo("disk-hit");
                    assertThat(read(pinned)).isEqualTo(status == 304 ? "body" : "new!");
                }
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeDuringDownloadRetainsOwnershipThroughPublicationOrFailure(boolean failDownload) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        InputStream body = new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                entered.countDown();
                await(release);
                return failDownload ? -1 : super.read(bytes, offset, length);
            }
        };
        fixture.responder = request -> response(200, body, Map.of("Content-Length", "4"));
        try (var cache = fixture.cache();
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var pending = workers.submit(() -> cache.acquire(IMAGE, null, null));
                await(entered);
                cache.close();
                assertDirectoryOwned();
                release.countDown();
                if (failDownload) {
                    assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(dev.mdz.wolpi.exceptions.HttpStatusException.class);
                } else {
                    try (var acquired = pending.get(5, TimeUnit.SECONDS)) {
                        assertThat(read(acquired)).isEqualTo("body");
                        assertDirectoryOwned();
                    }
                }
                try (var replacement = fixture.cache();
                        var hit = replacement.tryAcquireFresh(IMAGE, null, null)) {
                    if (failDownload) assertThat(hit).isNull();
                    else assertThat(read(hit)).isEqualTo("body");
                }
                try (var paths = Files.list(directory)) {
                    assertThat(paths.map(path -> path.getFileName().toString()).toList())
                            .noneMatch(name -> name.startsWith(".download-"));
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void retiredLeaseKeepsOwnershipAfterTheCurrentLeaseCloses() throws Exception {
        try (var cache = fixture.cache();
                var retired = cache.acquire(IMAGE, null, null)) {
            fixture.clock.advance(61);
            fixture.responder = request -> response(200, "new!", Map.of("Content-Length", "4"));
            try (var current = cache.acquire(IMAGE, null, null)) {
                cache.close();
            }
            assertDirectoryOwned();
            assertThat(read(retired)).isEqualTo("body");
            Path oldPath = retired.path();
            retired.close();
            assertThat(Files.exists(oldPath)).isFalse();
            try (var replacement = fixture.cache();
                    var pinned = replacement.acquire(IMAGE, null, null)) {
                assertThat(read(pinned)).isEqualTo("new!");
                cache.close();
                assertThat(Files.exists(pinned.path())).isTrue();
            }
        }
    }

    @Test
    void directStreamsDoNotKeepDirectoryOwnershipAfterAcquisition() throws Exception {
        fixture.responder = request -> response(200, "body", Map.of());
        try (var cache = fixture.cache();
                var direct = cache.acquire(IMAGE, null, null)) {
            cache.close();
            try (var replacement = fixture.cache()) {
                assertThat(direct.canReopenBody()).isFalse();
                assertThat(read(direct)).isEqualTo("body");
            }
        }
    }
}
