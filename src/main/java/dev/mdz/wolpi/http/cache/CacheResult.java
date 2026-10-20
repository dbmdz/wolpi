package dev.mdz.wolpi.http.cache;

/// Diagnostic status for `X-Wolpi-Http-Source-Cache`, either a cached body outcome or a bypass
sealed interface CacheResult permits CacheOutcome, CacheBypassReason {
    /// Value for the response header
    String headerValue();
}
