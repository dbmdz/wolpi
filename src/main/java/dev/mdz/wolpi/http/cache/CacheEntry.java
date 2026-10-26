package dev.mdz.wolpi.http.cache;

import dev.mdz.wolpi.model.ImageInfo;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/// Response metadata for one cached body. Fields are fixed at creation, except for the extracted
/// image metadata, which is filled in after the body has been published.
final class CacheEntry {
    /// HTTP headers that are stored from the response
    private static final Set<String> STORED_HEADERS = Set.of(
            "cache-control",
            "etag",
            "last-modified",
            "date",
            "age",
            "expires",
            "vary",
            "content-length",
            "content-type",
            "content-encoding");

    /// Cache key for this entry
    final String key;

    /// Retained headers from the upstream response
    final HttpHeaders headers;

    /// Last time the entry was validated
    final Instant validatedAt;

    /// Age of the response when the entry was created
    final Duration initialAge;

    /// The body as it is stored on disk
    final DiskBody diskBody;

    /// Image metadata for the image in the response body, set after publication
    // Multiple requests may extract equivalent metadata concurrently; publish it to all readers.
    volatile @Nullable ImageInfo imageInfo;

    CacheEntry(String key, HttpHeaders headers, Instant validatedAt, Duration initialAge, DiskBody diskBody) {
        this.key = key;
        HttpHeaders retained = new HttpHeaders();
        headers.forEach((name, values) -> {
            if (STORED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                retained.put(name, List.copyOf(values));
            }
        });
        this.headers = HttpHeaders.readOnlyHttpHeaders(retained);
        this.validatedAt = validatedAt;
        this.initialAge = initialAge;
        this.diskBody = diskBody;
    }

    /// A downloaded body that the disk store has not published yet. The reservation owns the
    /// pending file and its length, so both are absent here.
    record Pending(String key, HttpHeaders headers, Instant validatedAt, Duration initialAge) {}

    /// One complete file. Each pin means a request may still read it.
    /// Retired files accept no new readers; delete them when the last reader leaves.
    /// Read or change pins and retired only while holding the owning DiskStore lock.
    /// Path and length never change and need no lock to read.
    static final class DiskBody {
        final Path path;
        final long length;
        private int pins;
        private boolean retired;

        DiskBody(Path path, long length) {
            this.path = path;
            this.length = length;
        }

        /// Whether a request currently reads this body.
        boolean pinned() {
            return pins != 0;
        }

        /// Whether the store removed this body from its index.
        boolean retired() {
            return retired;
        }

        /// Retain this current body for one more reader.
        void pin() {
            assert !retired;
            pins++;
        }

        /// Release one reader.
        ///
        /// @return true when the body is retired and its file can be removed
        /// @throws IllegalStateException when no reader holds this body
        boolean unpin() {
            if (pins <= 0) {
                throw new IllegalStateException("Unbalanced HTTP source cache pin");
            }
            pins--;
            return retired && pins == 0;
        }

        /// Stop handing out this body and await its remaining readers.
        ///
        /// @return true when no reader holds it and its file can be removed
        boolean retire() {
            retired = true;
            return pins == 0;
        }
    }
}
