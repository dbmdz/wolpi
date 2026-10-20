package dev.mdz.wolpi.http.cache;

/// Outcome of acquiring a body that the cache retains, independent of its stored response metadata.
enum CacheOutcome implements CacheResult {
    /// A fresh stored body satisfied this acquisition without an origin request.
    HIT("disk-hit"),
    /// The cache downloaded and published a complete body.
    DOWNLOADED("miss"),
    /// An origin `304` confirmed the stored body.
    REVALIDATED("revalidated");

    private final String headerValue;

    CacheOutcome(String headerValue) {
        this.headerValue = headerValue;
    }

    @Override
    public String headerValue() {
        return headerValue;
    }
}
