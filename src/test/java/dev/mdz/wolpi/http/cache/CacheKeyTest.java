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
    void omittedIdentityPreservesUrlAndHeaderPartitioning() {
        var first = new HttpHeaders();
        first.setBearerAuth("first");
        var second = new HttpHeaders();
        second.setBearerAuth("second");
        assertThat(CacheKey.create(FIRST, first, null))
                .isEqualTo(CacheKey.create(FIRST, first, null, null))
                .isNotEqualTo(CacheKey.create(SECOND, first, null))
                .isNotEqualTo(CacheKey.create(FIRST, second, null));
    }

    @Test
    void explicitIdentityReplacesBothFetchUrlAndHeaders() {
        var first = new HttpHeaders();
        first.setBearerAuth("first");
        first.set("X-Amz-Date", "20260101T000000Z");
        var second = new HttpHeaders();
        second.setBearerAuth("second");
        second.set("X-Amz-Date", "20260101T000001Z");
        assertThat(CacheKey.create(FIRST, first, null, "resolver:tenant-a:object"))
                .isEqualTo(CacheKey.create(SECOND, second, null, "resolver:tenant-a:object"))
                .isNotEqualTo(CacheKey.create(SECOND, second, null, "resolver:tenant-b:object"))
                .isNotEqualTo(CacheKey.create(SECOND, second, null, "resolver:tenant-a:other-object"));
    }

    @Test
    void explicitAndDefaultIdentityNamespacesAreSeparate() {
        var headers = new HttpHeaders();
        assertThat(CacheKey.create(FIRST, headers, null, FIRST.toString()))
                .isNotEqualTo(CacheKey.create(FIRST, headers, null));
    }

    @Test
    void resolverValidatorsStillPartitionExplicitIdentities() {
        var headers = new HttpHeaders();
        var v1 = new CacheInfo("v1", Instant.parse("2026-01-01T00:00:00Z"));
        var v2 = new CacheInfo("v2", v1.lastModified());
        var later = new CacheInfo("v1", v1.lastModified().plusSeconds(1));
        assertThat(CacheKey.create(FIRST, headers, v1, "object"))
                .isNotEqualTo(CacheKey.create(FIRST, headers, v2, "object"))
                .isNotEqualTo(CacheKey.create(FIRST, headers, later, "object"))
                .isNotEqualTo(CacheKey.create(FIRST, headers, null, "object"));
    }
}
