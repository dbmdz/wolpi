package dev.mdz.wolpi.http.cache;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/// Shared-cache rules for complete HTTP responses, including bounded reuse after origin failures.
final class CachePolicy {
    private CachePolicy() {}

    /// Parse the `Cache-Control` header and return the directives it contained as a map.
    static Map<String, String> directives(HttpHeaders headers) {
        Map<String, String> result = new HashMap<>();
        for (String value : headers.getOrEmpty(HttpHeaders.CACHE_CONTROL)) {
            // Unfortunately Spring doesn't have a utility to parse this, so this is a homebrew
            // single-pass parser for the Cache-Control value.
            int start = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                if (escaped) {
                    escaped = false;
                } else if (quoted && character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    quoted = !quoted;
                } else if (character == ',' && !quoted) {
                    addDirective(result, value.substring(start, i));
                    start = i + 1;
                }
            }
            addDirective(result, value.substring(start));
        }
        return result;
    }

    /// Helper method to add a new Cache-Control directive to the output map
    private static void addDirective(Map<String, String> result, String directive) {
        String[] parts = directive.trim().split("=", 2);
        String name = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (name.isEmpty()) {
            return;
        }
        String argument = parts.length == 2 ? parts[1].trim() : "";
        if (argument.startsWith("\"") && argument.endsWith("\"") && argument.length() >= 2) {
            argument = argument.substring(1, argument.length() - 1);
        } else if (argument.contains("\"")) {
            argument = "invalid";
        }
        // Conflicting repeated freshness directives must not extend freshness.
        result.merge(name, argument, (first, second) -> first.equals(second) ? first : "invalid");
    }

    /// Check if the HTTP request headers from the resolver indicate a cache bypass
    ///
    /// @return the reason for the cache bypass or null if no bypass was indicated
    static @Nullable CacheBypassReason requestBypassReason(HttpHeaders headers) {
        // No caching at all requested
        if (directives(headers).containsKey("no-store")) {
            return CacheBypassReason.REQUEST_NO_STORE;
        }
        // We don't support partial responses
        if (headers.containsHeader(HttpHeaders.RANGE) || headers.containsHeader(HttpHeaders.IF_RANGE)) {
            return CacheBypassReason.RANGE_REQUEST;
        }
        // Resolver seems to want its own caching handling
        if (headers.containsHeader(HttpHeaders.IF_MATCH) || headers.containsHeader(HttpHeaders.IF_UNMODIFIED_SINCE)) {
            return CacheBypassReason.PRECONDITION;
        }
        return null;
    }

    /// Check if the HTTP response headers from the upstream indicate a cache bypass.
    ///
    /// @return the reason for the cache bypass or null if no bypass was indicated
    static @Nullable CacheBypassReason responseBypassReason(HttpHeaders request, HttpHeaders response) {
        Map<String, String> control = directives(response);
        // No caching at all desired
        if (control.containsKey("no-store")) {
            return CacheBypassReason.NO_STORE;
        }
        // Source contains private content that must not be cached
        if (control.containsKey("private")) {
            return CacheBypassReason.PRIVATE;
        }
        // Vary: * means we have nothing to key the cache entry by
        if (response.getVary().contains("*")) {
            return CacheBypassReason.VARY_STAR;
        }
        // Varying on If-Modified-Since/If-Modified is illegal
        if (variesOnValidators(response)) {
            return CacheBypassReason.VARY_VALIDATOR;
        }
        // Cookies make caching impractical
        if (response.containsHeader(HttpHeaders.SET_COOKIE)) {
            return CacheBypassReason.SET_COOKIE;
        }
        // Authenticated requests for non-public resources must not be cached
        if ((request.containsHeader(HttpHeaders.AUTHORIZATION) || request.containsHeader(HttpHeaders.COOKIE))
                && !control.containsKey("public")
                && !control.containsKey("s-maxage")
                && !control.containsKey("must-revalidate")) {
            return CacheBypassReason.AUTHENTICATED;
        }
        return null;
    }

    /// Check for the very rare case where an upstream has one of our conditional request headers in its `Vary`
    /// declaration. The consequences of that would be dire, so we better check.
    static boolean variesOnValidators(HttpHeaders response) {
        return response.getVary().stream()
                .anyMatch(name -> CacheKey.VALIDATORS.contains(name.toLowerCase(java.util.Locale.ROOT)));
    }

    /// Check if a cache entry is still fresh
    static boolean fresh(CacheEntry entry, Duration maxFreshness, HttpHeaders request, Instant now) {
        // Does the request force us to revalidate?
        Map<String, String> requestControl = directives(request);
        if (requiresValidation(requestControl)) {
            return false;
        }

        // Does the cached entry itself force us to revalidate?
        Map<String, String> entryControl = directives(entry.headers);
        if (entryControl.containsKey("no-cache")) {
            return false;
        }

        return now.isBefore(freshUntil(entry, maxFreshness, entryControl));
    }

    /// Allow fallback only within the configured window and when neither side requires validation.
    static boolean canServeStale(
            CacheEntry entry, Duration maxFreshness, Duration staleIfError, HttpHeaders request, Instant now) {
        if (staleIfError.isZero()
                || requestBypassReason(request) != null
                || requiresValidation(directives(request))
                || responseBypassReason(request, entry.headers) != null) {
            return false;
        }
        Map<String, String> control = directives(entry.headers);
        if (control.containsKey("no-cache")
                || control.containsKey("must-revalidate")
                || control.containsKey("proxy-revalidate")
                || control.containsKey("s-maxage")) {
            return false;
        }
        Instant expiredAt = freshUntil(entry, maxFreshness, control);
        return !now.isBefore(expiredAt) && now.isBefore(expiredAt.plus(staleIfError));
    }

    private static boolean requiresValidation(Map<String, String> control) {
        return control.containsKey("no-cache")
                || (control.containsKey("max-age") && seconds(control.get("max-age")) == 0);
    }

    private static Instant freshUntil(CacheEntry entry, Duration maxFreshness, Map<String, String> control) {
        Instant cap = entry.validatedAt.plus(maxFreshness);
        Instant origin = originDeadline(entry, control);
        return origin != null && origin.isBefore(cap) ? origin : cap;
    }

    static Duration initialAge(HttpHeaders headers, Instant sentAt, Instant receivedAt) {
        Instant date = date(headers.getFirst(HttpHeaders.DATE));
        Duration apparentAge = date == null ? Duration.ZERO : nonnegative(Duration.between(date, receivedAt));
        Duration correctedAge = Duration.ofSeconds(seconds(headers.getFirst(HttpHeaders.AGE)))
                .plus(nonnegative(Duration.between(sentAt, receivedAt)));
        return apparentAge.compareTo(correctedAge) > 0 ? apparentAge : correctedAge;
    }

    private static @Nullable Instant originDeadline(CacheEntry entry, Map<String, String> control) {
        // s-maxage overrides max-age for shared caches.
        String maxAge = control.containsKey("s-maxage") ? control.get("s-maxage") : control.get("max-age");
        Duration lifetime;
        if (maxAge != null) {
            lifetime = Duration.ofSeconds(seconds(maxAge));
        } else {
            String expiresHeader = entry.headers.getFirst(HttpHeaders.EXPIRES);
            if (expiresHeader == null) {
                return null;
            }
            Instant expires = date(expiresHeader);
            Instant date = date(entry.headers.getFirst(HttpHeaders.DATE));
            lifetime = expires == null
                    ? Duration.ZERO
                    : nonnegative(Duration.between(date == null ? entry.validatedAt : date, expires));
        }
        // Lifetime counts from origin generation, so the age at receipt is already spent.
        return entry.validatedAt.plus(lifetime.minus(entry.initialAge));
    }

    static long seconds(@Nullable String value) {
        if (value == null) {
            return 0;
        }
        try {
            // RFC delta-seconds permits only decimal digits.
            if (!value.matches("[0-9]+")) {
                return 0;
            }
            return Math.min(Long.parseLong(value), Integer.MAX_VALUE);
        } catch (NumberFormatException e) {
            return value.matches("[0-9]+") ? Integer.MAX_VALUE : 0;
        }
    }

    private static Duration nonnegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static @Nullable Instant date(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }
}
