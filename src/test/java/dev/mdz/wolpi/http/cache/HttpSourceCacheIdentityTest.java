package dev.mdz.wolpi.http.cache;

import static dev.mdz.wolpi.http.cache.CacheFixture.read;
import static dev.mdz.wolpi.http.cache.CacheFixture.response;
import static org.assertj.core.api.Assertions.assertThat;

import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSize;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HttpSourceCacheIdentityTest {
    private static final ImageInfo IMAGE_INFO = new ImageInfo("jpeg", new ImageSize(10, 20), List.of(), List.of());

    @TempDir
    Path directory;

    private CacheFixture fixture;

    @BeforeEach
    void setup() throws Exception {
        fixture = new CacheFixture(directory);
    }

    private static HttpResolvedImage signed(String signature, String identity) {
        return new HttpResolvedImage(
                URI.create("https://storage.example/image?X-Amz-Signature=" + signature),
                Map.of("X-Fetch-Signature", signature),
                identity);
    }

    @Test
    void rotatedFetchUrlsAndHeadersReuseTheBodyAndExtractedMetadata() throws Exception {
        var first = signed("first", "resolver:tenant-a:object");
        var second = signed("second", first.cacheIdentity());
        try (var cache = fixture.cache()) {
            try (var warm = cache.acquire(first, null)) {
                warm.cacheImageInfo(IMAGE_INFO);
            }
            try (var hit = cache.tryAcquireFresh(second, null)) {
                assertThat(hit).isNotNull();
                assertThat(hit.cacheStatusHeaderValue()).isEqualTo("disk-hit");
                assertThat(hit.imageInfo()).isEqualTo(IMAGE_INFO);
                assertThat(read(hit)).isEqualTo("body");
            }
            assertThat(fixture.requests).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 304})
    void revalidationUsesCurrentCredentialsAndUpdatesBodyMetadata(int status) throws Exception {
        var first = signed("expired", "resolver:tenant-a:object");
        var second = signed("current", first.cacheIdentity());
        try (var cache = fixture.cache()) {
            try (var warm = cache.acquire(first, null)) {
                warm.cacheImageInfo(IMAGE_INFO);
            }
            fixture.clock.advance(61);
            fixture.responder = request -> {
                assertThat(request.uri()).isEqualTo(second.url());
                assertThat(request.headers().firstValue("X-Fetch-Signature")).contains("current");
                assertThat(request.headers().firstValue("If-None-Match")).contains("W/\"v1\"");
                return response(status, status == 304 ? "" : "new!", Map.of("Content-Length", "4"));
            };
            assertThat(cache.tryAcquireFresh(second, null)).isNull();
            try (var result = cache.acquire(second, null)) {
                assertThat(result.cacheStatusHeaderValue()).isEqualTo(status == 304 ? "revalidated" : "miss");
                assertThat(result.imageInfo()).isEqualTo(status == 304 ? IMAGE_INFO : null);
                assertThat(read(result)).isEqualTo(status == 304 ? "body" : "new!");
            }
            assertThat(fixture.requests).hasValue(2);
        }
    }

    @ParameterizedTest
    @CsvSource({"resolver:tenant-b:object,v1", "resolver:tenant-a:object,v2"})
    void identitiesAndResolverVersionsIsolateBodiesAndMetadata(String identity, String version) throws Exception {
        var first = signed("first", "resolver:tenant-a:object");
        var second = signed("first", identity);
        var targetVersion = new CacheInfo(version, null);
        try (var cache = fixture.cache()) {
            try (var warm = cache.acquire(first, new CacheInfo("v1", null))) {
                warm.cacheImageInfo(IMAGE_INFO);
            }
            assertThat(cache.tryAcquireFresh(second, targetVersion)).isNull();
            try (var result = cache.acquire(second, targetVersion)) {
                assertThat(result.cacheStatusHeaderValue()).isEqualTo("miss");
                assertThat(result.imageInfo()).isNull();
            }
            assertThat(fixture.requests).hasValue(2);
        }
    }

    @Test
    void stableIdentitySurvivesRestartWithoutPersistingSecrets() throws Exception {
        var signed = signed("secret-first", "resolver:secret-tenant:secret-object");
        var first = new HttpResolvedImage(
                signed.url(), Map.of("Authorization", "secret-credential"), signed.cacheIdentity());
        var second = signed("secret-second", first.cacheIdentity());
        fixture.responder = request -> response(200, "body", Map.of("Content-Length", "4", "Cache-Control", "public"));
        try (var cache = fixture.cache();
                var warm = cache.acquire(first, null)) {}
        try (var cache = fixture.cache();
                var hit = cache.acquire(second, null)) {
            assertThat(hit.cacheStatusHeaderValue()).isEqualTo("disk-hit");
            assertThat(read(hit)).isEqualTo("body");
        }
        assertThat(fixture.requests).hasValue(1);
        try (var paths = Files.list(directory)) {
            for (Path metadata :
                    paths.filter(path -> path.toString().endsWith(".json")).toList()) {
                assertThat(Files.readString(metadata))
                        .doesNotContain(
                                first.cacheIdentity(),
                                "storage.example",
                                "secret-first",
                                "secret-second",
                                "secret-credential");
            }
        }
    }

    @Test
    void stableIdentityDoesNotOverrideRequestNoStore() throws Exception {
        var first = signed("first", "resolver:object");
        var second = new HttpResolvedImage(
                signed("second", "unused").url(), Map.of("Cache-Control", "no-store"), first.cacheIdentity());
        try (var cache = fixture.cache()) {
            try (var warm = cache.acquire(first, null)) {}
            assertThat(cache.tryAcquireFresh(second, null)).isNull();
            try (var direct = cache.acquire(second, null)) {
                assertThat(direct.cacheStatusHeaderValue()).isEqualTo("bypass=request-no-store");
            }
            assertThat(fixture.requests).hasValue(2);
        }
    }

    @Test
    void stableIdentityStillChecksStoragePermissionForCurrentRequestHeaders() throws Exception {
        var first = signed("first", "resolver:object");
        var second = new HttpResolvedImage(
                signed("second", "unused").url(), Map.of("Authorization", "Bearer token"), first.cacheIdentity());
        try (var cache = fixture.cache()) {
            try (var warm = cache.acquire(first, null)) {}
            assertThat(cache.tryAcquireFresh(second, null)).isNull();
            fixture.responder = request -> {
                assertThat(request.uri()).isEqualTo(second.url());
                assertThat(request.headers().firstValue("Authorization")).contains("Bearer token");
                return response(304, "", Map.of());
            };
            try (var result = cache.acquire(second, null)) {
                assertThat(result.cacheStatusHeaderValue()).isEqualTo("bypass=authenticated");
                assertThat(read(result)).isEqualTo("body");
            }
            assertThat(cache.tryAcquireFresh(first, null)).isNull();
        }
    }
}
