package dev.mdz.wolpi.http.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;

class CachePolicyTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration MAX_FRESHNESS = Duration.ofSeconds(30);

    private CacheEntry entry(HttpHeaders headers, Instant sent, Instant received) {
        return new CacheEntry(
                "key",
                headers,
                received,
                CachePolicy.initialAge(headers, sent, received),
                new CacheEntry.DiskBody(java.nio.file.Path.of("body"), 4));
    }

    private boolean fresh(CacheEntry entry, long seconds) {
        return CachePolicy.fresh(entry, MAX_FRESHNESS, new HttpHeaders(), NOW.plusSeconds(seconds));
    }

    private static String date(Instant value) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(value.atZone(ZoneOffset.UTC));
    }

    @ParameterizedTest
    @CsvSource({
        "Cache-Control,no-store,NO_STORE",
        "Cache-Control,private,PRIVATE",
        "Cache-Control,private=\"ETag\",PRIVATE",
        "Vary,*,VARY_STAR",
        "Vary,If-None-Match,VARY_VALIDATOR",
        "Vary,If-Modified-Since,VARY_VALIDATOR",
        "Set-Cookie,session=token,SET_COOKIE"
    })
    void responseHeadersCanForbidStorage(String header, String value, CacheBypassReason reason) {
        HttpHeaders response = new HttpHeaders();
        response.set(header, value);
        assertThat(CachePolicy.responseBypassReason(new HttpHeaders(), response))
                .isEqualTo(reason);
    }

    @Test
    void sMaxAgeTakesPrecedenceAndAgeReducesFreshness() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("max-age=900, s-maxage=10");
        headers.set(HttpHeaders.AGE, "7");
        headers.setDate(NOW);
        CacheEntry entry = entry(headers, NOW, NOW);
        assertThat(fresh(entry, 2)).isTrue();
        assertThat(fresh(entry, 3)).isFalse();
    }

    @Test
    void apparentAgeAndResponseDelayAreAccountedFor() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("max-age=20");
        headers.set(HttpHeaders.DATE, date(NOW.minusSeconds(10)));
        headers.set(HttpHeaders.AGE, "1");
        CacheEntry entry = entry(headers, NOW.minusSeconds(2), NOW);
        assertThat(entry.initialAge).isEqualTo(Duration.ofSeconds(10));
        assertThat(fresh(entry, 9)).isTrue();
        assertThat(fresh(entry, 10)).isFalse();
        headers.setDate(NOW);
        headers.set(HttpHeaders.AGE, "10");
        entry = entry(headers, NOW.minusSeconds(2), NOW);
        assertThat(entry.initialAge).isEqualTo(Duration.ofSeconds(12));
        assertThat(fresh(entry, 8)).isFalse();
    }

    @Test
    void expiresUsesDateAndDoesNotDoubleSubtractAge() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.DATE, date(NOW.minusSeconds(10)));
        headers.set(HttpHeaders.EXPIRES, date(NOW.plusSeconds(10)));
        headers.set(HttpHeaders.AGE, "10");
        CacheEntry entry = entry(headers, NOW, NOW);
        assertThat(fresh(entry, 9)).isTrue();
        assertThat(fresh(entry, 10)).isFalse();
    }

    @Test
    void maxAgeOverridesExpiresAndMaximumFreshnessCapsBoth() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("max-age=5");
        headers.setExpires(NOW.plusSeconds(500));
        assertThat(fresh(entry(headers, NOW, NOW), 5)).isFalse();
        headers.setCacheControl("max-age=3600");
        assertThat(fresh(entry(headers, NOW, NOW), 29)).isTrue();
        assertThat(fresh(entry(headers, NOW, NOW), 30)).isFalse();
    }

    @Test
    void missingFreshnessUsesMaximumFreshness() {
        CacheEntry entry = entry(new HttpHeaders(), NOW, NOW);
        assertThat(fresh(entry, 29)).isTrue();
        assertThat(fresh(entry, 30)).isFalse();
    }

    @Test
    void noCacheIncludingFieldNamesRequiresValidation() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("public, no-cache=\"ETag, Last-Modified\", max-age=3600");
        assertThat(fresh(entry(headers, NOW, NOW), 0)).isFalse();
        assertThat(CachePolicy.directives(headers)).containsKey("no-cache");
    }

    @Test
    void invalidAndConflictingLifetimeDirectivesAreNotFresh() {
        for (String value : new String[] {"max-age=-1", "max-age=invalid", "max-age=30, max-age=600", "max-age=\"30"}) {
            HttpHeaders headers = new HttpHeaders();
            headers.setCacheControl(value);
            assertThat(fresh(entry(headers, NOW, NOW), 0)).as(value).isFalse();
        }
    }

    @Test
    void hugeDeltaSecondsAreSaturatedWithoutOverflow() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("s-maxage=999999999999999999999999999999");
        headers.set(HttpHeaders.AGE, "999999999999999999999999999999");
        assertThat(fresh(entry(headers, NOW, NOW), 0)).isFalse();
    }

    @Test
    void requestNoCacheAndZeroMaxAgeRequireValidation() {
        CacheEntry entry = entry(new HttpHeaders(), NOW, NOW);
        for (String directive : new String[] {"no-cache", "max-age=0"}) {
            HttpHeaders request = new HttpHeaders();
            request.setCacheControl(directive);
            assertThat(CachePolicy.fresh(entry, MAX_FRESHNESS, request, NOW)).isFalse();
        }
    }
}
