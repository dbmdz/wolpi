package dev.mdz.wolpi.http.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mdz.wolpi.model.CacheInfo;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class CacheKeyTest {
    private static final URI FIRST = URI.create("https://storage.example/image?signature=first");
    private static final URI SECOND = URI.create("https://storage.example/image?signature=second");

    @Test
    void urlAndRequestHeadersPartitionBodies() {
        var first = new HttpHeaders();
        first.setBearerAuth("first");
        var second = new HttpHeaders();
        second.setBearerAuth("second");
        assertThat(CacheKey.create(FIRST, first, null))
                .isNotEqualTo(CacheKey.create(SECOND, first, null))
                .isNotEqualTo(CacheKey.create(FIRST, second, null));
    }

    @Test
    void resolverValidatorsPartitionSourceVersions() {
        var headers = new HttpHeaders();
        var v1 = new CacheInfo("v1", Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(CacheKey.create(FIRST, headers, v1))
                .isNotEqualTo(CacheKey.create(FIRST, headers, new CacheInfo("v2", v1.lastModified())))
                .isNotEqualTo(CacheKey.create(
                        FIRST, headers, new CacheInfo("v1", v1.lastModified().plusSeconds(1))))
                .isNotEqualTo(CacheKey.create(FIRST, headers, null));
    }
}
