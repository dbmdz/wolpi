package dev.mdz.wolpi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ImageSourceTest {
    @Test
    void resolverValidatorsRemainAuthoritativeWhenSourceValidatorsChange() {
        var resolver = new CacheInfo("resolver-version", null);
        var source = new ImageSource(
                "image", new HttpResolvedImage(URI.create("https://example.com/image"), null), null, resolver);
        source.updateSourceValidators(new CacheInfo("origin-version", null));
        assertThat(source.cacheInfo()).isEqualTo(resolver);
        source.updateSourceValidators(null);
        assertThat(source.cacheInfo()).isEqualTo(resolver);
        assertThat(source.resolverValidators()).isEqualTo(resolver);
    }

    @Test
    void sourceValidatorsCanBeReplacedAndClearedWithoutChangingIdentity() {
        var source = new ImageSource(
                "image", new HttpResolvedImage(URI.create("https://example.com/image"), null), null, null);
        source.updateSourceValidators(new CacheInfo("v1", null));
        source.updateSourceValidators(new CacheInfo("v2", null));
        assertThat(source.cacheInfo()).isEqualTo(new CacheInfo("v2", null));
        source.updateSourceValidators(null);
        assertThat(source.cacheInfo()).isNull();
        assertThat(source.resolverValidators()).isNull();
    }

    @Test
    void replacingTheLeasePreservesTheInitialDiagnostic() {
        var source = new ImageSource(
                "image", new HttpResolvedImage(URI.create("https://example.com/image"), null), null, null);
        var first = mock(HttpSourceLease.class);
        var reopened = mock(HttpSourceLease.class);
        when(first.cacheStatusHeaderValue()).thenReturn("miss");
        when(reopened.cacheStatusHeaderValue()).thenReturn("disk-hit");
        source.setHttpSourceLease(first);
        source.setHttpSourceLease(reopened);
        assertThat(source.httpSourceLease()).isSameAs(reopened);
        assertThat(source.httpSourceCacheStatus()).isEqualTo("miss");
    }
}
