package dev.mdz.wolpi.http.cache;

import java.util.Locale;

/// Reasons an acquisition is not retained for future reuse; tokens appear in
/// `X-Wolpi-Http-Source-Cache`.
enum CacheBypassReason implements CacheResult {
    DISABLED,
    REQUEST_NO_STORE,
    RANGE_REQUEST,
    PRECONDITION,
    NO_STORE,
    PRIVATE,
    VARY_STAR,
    VARY_VALIDATOR,
    SET_COOKIE,
    AUTHENTICATED,
    PARTIAL_RESPONSE,
    UPSTREAM_STATUS,
    UNKNOWN_LENGTH,
    EMPTY_BODY,
    RESOURCE_TOO_LARGE,
    CACHE_TOO_SMALL,
    CAPACITY,
    STORAGE_ERROR;

    /// Diagnostic token, derived from the constant name
    String token() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Override
    public String headerValue() {
        return "bypass=" + token();
    }
}
