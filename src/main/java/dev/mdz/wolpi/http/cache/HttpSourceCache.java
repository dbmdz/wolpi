package dev.mdz.wolpi.http.cache;

import dev.mdz.wolpi.config.HttpSourceCacheConfig;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.exceptions.HttpStatusException;
import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.HttpSourceLease;
import dev.mdz.wolpi.model.ImageInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/// Fetches original HTTP images and reuses complete downloads from disk.
/// Client-facing IIIF cache conditions belong to [dev.mdz.wolpi.controller.IIIFImageAPIController].
///
/// Three locks protect different things:
/// - `AcquisitionLock.lock` (per-key): only one request may fetch or revalidate a given key at a time.
/// - `synchronized (this)`: keeps shutdown from racing with requests. It protects the per-key
///   lock map and counts requests and file leases still using the cache. Hold it while choosing
///   and pinning a file so shutdown cannot intervene.
/// - `DiskStore`'s synchronized methods: protect cached entries, disk-space counts, and file pins.
///   A pin keeps a file from being deleted while a request uses it.
///
/// If locks are needed together, take them in that order: per-key → cache → store.
/// Never hold the cache or store lock while waiting for the per-key lock. Release the cache and
/// store locks before network calls or copying a response body; keep the per-key lock for that work.
/// Metadata writes and eviction can run with the cache and store locks held.
@Component
public final class HttpSourceCache implements AutoCloseable {
    public static final String RESPONSE_HEADER = "X-Wolpi-Http-Source-Cache";

    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final HttpSourceCacheConfig config;
    private final HttpClient client;
    private final Clock clock;
    private final Duration requestTimeout;

    /// The actual on-disk store for cache entries
    private final @Nullable DiskStore disk;

    /// Per-key locks and the callers using them. Read or change this map under synchronized (this).
    private final Map<String, AcquisitionLock> acquisitions = new HashMap<>();

    // Guarded by this monitor. Accepted acquisitions may finish after close() requests shutdown.
    private boolean closing;
    private int activeAcquisitions;
    private int activeFileLeases;

    @Autowired
    public HttpSourceCache(WolpiConfig config, HttpClient client) {
        this(
                config.httpSourceCache(),
                config.dataDirectory(),
                client,
                Clock.systemUTC(),
                config.httpTimeouts().forSources().request());
    }

    public HttpSourceCache(
            HttpSourceCacheConfig config, Path dataDirectory, HttpClient client, Clock clock, Duration requestTimeout) {
        AcquisitionDeadline.validate(requestTimeout);
        this.config = config;
        this.client = client;
        this.clock = clock;
        this.requestTimeout = requestTimeout;
        if (config.enabled()) {
            Path directory =
                    config.directory() == null ? dataDirectory.resolve("http-source-cache") : config.directory();
            try {
                disk = new DiskStore(directory, config.maxSize().toBytes());
            } catch (IOException e) {
                throw new IllegalStateException("Could not initialize HTTP source cache directory " + directory, e);
            }
        } else {
            disk = null;
        }
    }

    /// Acquire using the default URL-and-header identity.
    public @Nullable Lease tryAcquireFresh(
            URI uri, @Nullable Map<String, String> resolverHeaders, @Nullable CacheInfo resolverValidators) {
        return tryAcquireFresh(new HttpResolvedImage(uri, resolverHeaders), resolverValidators);
    }

    /// Acquire a pinned fresh body without waiting for or starting an upstream acquisition.
    /// The caller must close the returned lease after lazy image decoding finishes.
    ///
    /// @return a lease or null if no fresh entry exists, caching is disabled, or request policy bypasses it
    /// @param source current fetch URL/headers and optional stable cache identity from the resolver
    /// @param resolverValidators if present, validators that partition source versions
    public synchronized @Nullable Lease tryAcquireFresh(
            HttpResolvedImage source, @Nullable CacheInfo resolverValidators) {
        ensureOpen();
        HttpHeaders finalRequestHeaders = requestHeaders(source.headers());
        if (disk == null || CachePolicy.requestBypassReason(finalRequestHeaders) != null) {
            return null;
        }
        String key = CacheKey.create(source.url(), finalRequestHeaders, resolverValidators, source.cacheIdentity());
        return tryAcquireFreshEntry(key, finalRequestHeaders);
    }

    /// Acquire using the default URL-and-header identity.
    public Lease acquire(URI uri, @Nullable Map<String, String> resolverHeaders, @Nullable CacheInfo resolverValidators)
            throws IOException, InterruptedException {
        return acquire(new HttpResolvedImage(uri, resolverHeaders), resolverValidators);
    }

    /// Acquire a body, waiting for another acquisition or fetching/revalidating as needed.
    /// The caller owns the returned lease, including when caching is bypassed.
    /// Fetches use the current source URL/headers even when its stable cache identity matches an older URL.
    public Lease acquire(HttpResolvedImage source, @Nullable CacheInfo resolverValidators)
            throws IOException, InterruptedException {
        try (var scope = beginAcquisition()) {
            return acquireBody(source, resolverValidators);
        }
    }

    private Lease acquireBody(HttpResolvedImage source, @Nullable CacheInfo resolverValidators)
            throws IOException, InterruptedException {
        // One budget for source acquisition, not for the whole IIIF request or image decoding.
        var deadline = new AcquisitionDeadline(requestTimeout);
        URI uri = source.url();
        HttpHeaders request = requestHeaders(source.headers());
        CacheBypassReason bypass = disk == null ? CacheBypassReason.DISABLED : CachePolicy.requestBypassReason(request);
        if (bypass != null) {
            return direct(uri, request, bypass, deadline);
        }

        String key = CacheKey.create(uri, request, resolverValidators, source.cacheIdentity());
        CacheEntry observed;
        AcquisitionLock acquisition;
        synchronized (this) {
            Lease fresh = tryAcquireFreshEntry(key, request);
            if (fresh != null) {
                return fresh;
            }
            // Register while observing the entry, so a completed acquisition cannot disappear
            // between this snapshot and joining its diagnostic result.
            observed = current(key);
            acquisition = acquisitions.computeIfAbsent(key, ignored -> new AcquisitionLock());
            acquisition.users++;
        }

        boolean locked = false;
        try {
            locked = acquisition.lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            if (!locked) {
                throw new HttpTimeoutException("HTTP source acquisition deadline exceeded");
            }
            // Make sure we haven't exceeded the deadline yet
            deadline.check();

            // At this point we're either the first caller to request the acquisition (and need to
            // fetch it ourselves), another caller has failed to fetch (and we need to retry
            // ourselves) or another caller was successful, and we can reuse the new entry.
            Lease previous;
            synchronized (this) {
                // Check if we now have a fresh cache entry and return it if that's the case
                Lease fresh = tryAcquireFreshEntry(key, request);
                if (fresh != null) {
                    return fresh;
                }

                CacheEntry latest = current(key);
                if (latest != null
                        && latest != observed
                        && acquisition.completedResult != null
                        && CachePolicy.responseBypassReason(request, latest.headers) == null) {
                    // Another acquisition completed while we waited. Share its validated result
                    // even if no-cache requires revalidation for subsequent, independent requests.
                    return lease(latest, acquisition.completedResult);
                }

                // If there's a stale entry on disk, pin it during revalidation so it doesn't
                // disappear, we might need it after all if the data is still fresh according
                // to the upstream
                previous = latest == null ? null : lease(latest, CacheOutcome.REVALIDATED);
            }

            Lease result = fetch(uri, request, key, previous, deadline);
            acquisition.completedResult = result.result;
            return result;
        } finally {
            if (locked) {
                acquisition.lock.unlock();
            }
            synchronized (this) {
                if (--acquisition.users == 0) {
                    acquisitions.remove(key);
                }
            }
        }
    }

    /// Fetch or revalidate while holding the per-key lock. Use the same deadline for retries.
    ///
    /// @param previous stale cached body; close it unless returning it to the caller
    private Lease fetch(
            URI uri, HttpHeaders requestHeaders, String key, @Nullable Lease previous, AcquisitionDeadline deadline)
            throws IOException, InterruptedException {
        try {
            HttpHeaders upstreamHeaders = copy(requestHeaders);
            // If we have an entry we need to revalidate it, clear any existing conditional
            // request headers and set our own
            if (previous != null) {
                upstreamHeaders.remove(HttpHeaders.IF_NONE_MATCH);
                upstreamHeaders.remove(HttpHeaders.IF_MODIFIED_SINCE);
                String etag = previous.responseHeaders().getETag();
                String modified = previous.responseHeaders().getFirst(HttpHeaders.LAST_MODIFIED);
                if (etag != null) {
                    upstreamHeaders.set(HttpHeaders.IF_NONE_MATCH, etag);
                } else if (modified != null) {
                    upstreamHeaders.set(HttpHeaders.IF_MODIFIED_SINCE, modified);
                }
            }

            // Perform the request
            Instant sentAt = clock.instant();
            HttpResponse<InputStream> response = send(uri, upstreamHeaders, deadline);
            Instant receivedAt = clock.instant();
            HttpHeaders headers = responseHeaders(response);

            if (response.statusCode() == 304 && previous != null) {
                // Server says the resource was not modified since our previous access, verify that
                // the revalidation was actually successful
                response.body().close(); // 304s have no body, close right away
                if (!validRevalidation(previous.responseHeaders(), headers, upstreamHeaders)
                        || CachePolicy.variesOnValidators(headers)) {
                    synchronized (this) {
                        invalidate(key);
                    }
                    previous.close();
                    previous = null;
                    // Retry without cache-injected conditions, preserving the resolver's request.
                    return fetch(uri, requestHeaders, key, null, deadline);
                } else {
                    // Update the cache entry; the revalidated entry reuses the pinned body.
                    revalidate(key, requestHeaders, previous, headers, sentAt, receivedAt);
                    Lease result = previous;
                    previous = null; // Transfer the pin to the caller instead of releasing it below.
                    return result;
                }
            } else {
                checkStatus(response, headers, CacheBypassReason.UPSTREAM_STATUS);
                if (previous != null) {
                    // Invalidate and close the previous cache lease.
                    synchronized (this) {
                        invalidate(key);
                    }
                    previous.close();
                    previous = null;
                }
                CacheBypassReason reason = cacheBypassReason(requestHeaders, headers, response.statusCode());
                if (reason == null) {
                    return download(key, response, headers, sentAt, receivedAt, deadline);
                } else {
                    return new Lease(null, response.body(), headers, reason);
                }
            }
        } finally {
            // Release the stale pin only if this method still owns it.
            if (previous != null) {
                previous.close();
            }
        }
    }

    /// Update headers after a 304, keeping the same file for this request. If the new headers
    /// forbid caching, remove the entry but let this request finish reading its pinned file.
    private void revalidate(
            String key, HttpHeaders request, Lease previous, HttpHeaders headers, Instant sentAt, Instant receivedAt) {
        HttpHeaders merged = copy(previous.responseHeaders());
        merged.remove(HttpHeaders.AGE);
        merged.remove(HttpHeaders.DATE);
        headers.forEach((name, values) -> {
            if (!name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH) && !name.equalsIgnoreCase(HttpHeaders.CONTENT_RANGE))
                merged.put(name, List.copyOf(values));
        });
        if (!merged.containsHeader(HttpHeaders.DATE))
            merged.set(
                    HttpHeaders.DATE, DateTimeFormatter.RFC_1123_DATE_TIME.format(receivedAt.atZone(ZoneOffset.UTC)));
        CacheBypassReason reason = CachePolicy.responseBypassReason(request, merged);
        CacheEntry old = previous.entry;
        CacheEntry refreshed = new CacheEntry(
                key, merged, receivedAt, CachePolicy.initialAge(merged, sentAt, receivedAt), old.diskBody);
        refreshed.imageInfo = old.imageInfo;
        synchronized (this) {
            if (reason != null) {
                invalidate(key);
            } else if (disk != null && !disk.refresh(old, refreshed)) {
                invalidate(key);
                reason = CacheBypassReason.STORAGE_ERROR;
            }
        }
        previous.entry = refreshed;
        // The current request still reuses its pinned file, but future requests cannot reuse it.
        previous.result = Objects.requireNonNullElse(reason, CacheOutcome.REVALIDATED);
    }

    /// Return the reason this response must not be cached, or `null` when it may be stored.
    private @Nullable CacheBypassReason cacheBypassReason(HttpHeaders request, HttpHeaders headers, int status) {
        CacheBypassReason reason = CachePolicy.responseBypassReason(request, headers);
        if (reason != null) {
            return reason;
        }
        if (status == 206 || headers.containsHeader(HttpHeaders.CONTENT_RANGE)) {
            return CacheBypassReason.PARTIAL_RESPONSE;
        }
        if (status != 200) {
            return CacheBypassReason.UPSTREAM_STATUS;
        }
        long length = contentLength(headers);
        if (length <= 0) {
            return length < 0 ? CacheBypassReason.UNKNOWN_LENGTH : CacheBypassReason.EMPTY_BODY;
        }
        if (length > config.maxResourceSize().toBytes()) {
            return CacheBypassReason.RESOURCE_TOO_LARGE;
        }
        if (length > config.maxSize().toBytes()) {
            return CacheBypassReason.CACHE_TOO_SMALL;
        }
        return null;
    }

    /// Download the response body to the disk store for a given cache key.
    private Lease download(
            String key,
            HttpResponse<InputStream> response,
            HttpHeaders headers,
            Instant sentAt,
            Instant receivedAt,
            AcquisitionDeadline deadline)
            throws IOException {
        // First, we need to make sure we have enough spare disk space in the cache
        long length = contentLength(headers);
        DiskStore.Reservation diskReservation;
        synchronized (this) {
            assert disk != null; // Only an enabled cache starts a cache-owned download.
            try {
                diskReservation = disk.reserve(length);
            } catch (IOException e) {
                // Abnormal error when reserving disk space, worth logging
                log.warn("Could not reserve HTTP source cache disk space", e);
                return new Lease(null, response.body(), headers, CacheBypassReason.STORAGE_ERROR);
            }
        }

        // Disk cache disabled or full without possible evictions
        if (diskReservation == null) {
            return new Lease(null, response.body(), headers, CacheBypassReason.CAPACITY);
        }

        try (DiskStore.Reservation reservation = diskReservation) {
            try (InputStream body = response.body()) {
                var timeout = deadline.closeOnExpiry(body);
                try (OutputStream out = Files.newOutputStream(reservation.path)) {
                    copyExactly(body, out, length, deadline);
                } finally {
                    timeout.cancel(false);
                }
            }
            CacheEntry.Pending pending = new CacheEntry.Pending(
                    key, headers, receivedAt, CachePolicy.initialAge(headers, sentAt, receivedAt));
            synchronized (this) {
                deadline.check();
                CacheEntry entry = disk.publish(reservation, pending);
                return lease(entry, CacheOutcome.DOWNLOADED);
            }
        }
    }

    /// Distinguish deadline-triggered closure from upstream failures (502) and premature EOF.
    private static int readUpstream(InputStream input, byte[] buffer, int length, AcquisitionDeadline deadline)
            throws IOException {
        try {
            int read = input.read(buffer, 0, length);
            deadline.check();
            return read;
        } catch (HttpTimeoutException e) {
            throw e;
        } catch (IOException e) {
            deadline.check();
            throw new HttpStatusException("Failed to read image source", 502, null, null, e);
        }
    }

    /// Helper method to copy from an [InputStream] to an [OutputStream] while honoring an [AcquisitionDeadline]
    private static void copyExactly(InputStream input, OutputStream output, long length, AcquisitionDeadline deadline)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            deadline.check();
            int read = readUpstream(input, buffer, (int) Math.min(buffer.length, remaining), deadline);
            if (read < 0) throw new HttpStatusException("Image source ended before Content-Length", 502, null, null);
            output.write(buffer, 0, read);
            remaining -= read;
        }
        deadline.check();
    }

    /// Try to obtain a fresh cache lease for the key from the disk cache.
    ///
    /// @return an existing and still fresh lease on the cache entry for the key, or `null` if
    /// the cache entry does not exist, is stale or is larger than the config permits.
    private @Nullable Lease tryAcquireFreshEntry(String key, HttpHeaders request) {
        CacheEntry stored = current(key);
        if (stored == null) {
            return null;
        }

        // If the config changed in terms of the maximum resource size since the entry was stored
        // we need to invalidate it
        if (stored.diskBody.length > config.maxResourceSize().toBytes()) {
            invalidate(key);
            return null;
        }
        // A stable identity may span changing request headers. Storage permission must still
        // hold for the current request, e.g. when it introduces Authorization or Cookie.
        return CachePolicy.responseBypassReason(request, stored.headers) == null
                        && CachePolicy.fresh(stored, config.maxFreshness(), request, clock.instant())
                ? lease(stored, CacheOutcome.HIT)
                : null;
    }

    /// Try to get the current cache entry for the given key
    private @Nullable CacheEntry current(String key) {
        return disk == null ? null : disk.find(key);
    }

    /// Pin the entry's file and count this lease so shutdown cannot unlock the directory yet.
    /// The caller must hold the cache lock, and the entry must still be in the store.
    private Lease lease(CacheEntry entry, CacheResult result) {
        assert disk != null;
        disk.pin(entry);
        activeFileLeases++;
        return new Lease(entry, null, null, result);
    }

    /// Invalidate the cache key by removing its entry from the disk cache
    private void invalidate(String key) {
        if (disk != null) {
            disk.remove(key);
        }
    }

    /// Throw if this cache is closed or in the process of being closed.
    private void ensureOpen() {
        if (closing) throw new IllegalStateException("HTTP source cache is closing");
    }

    /// Start counting this request so shutdown cannot unlock the directory while it is working.
    private synchronized AcquisitionScope beginAcquisition() {
        ensureOpen();
        activeAcquisitions++;
        return new AcquisitionScope();
    }

    /// Unlock the directory after close() was requested and all acquisitions and file leases finish.
    /// The caller must hold the cache lock.
    private void closeDiskIfIdle() throws IOException {
        if (closing && activeAcquisitions == 0 && activeFileLeases == 0 && disk != null) {
            disk.close();
        }
    }

    /// Create a lease that bypasses the cache by fetching the source directly
    private Lease direct(URI uri, HttpHeaders request, CacheBypassReason reason, AcquisitionDeadline deadline)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = send(uri, request, deadline);
        HttpHeaders headers = responseHeaders(response);
        checkStatus(response, headers, reason);
        return new Lease(null, response.body(), headers, reason);
    }

    /// Send with the remaining deadline and reject unsupported content coding before any body
    /// reaches the cache or decoder. Preserve upstream error statuses; their bodies are not decoded.
    private HttpResponse<InputStream> send(URI uri, HttpHeaders headers, AcquisitionDeadline deadline)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(deadline.remaining());
        headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 400 && hasUnsupportedContentEncoding(responseHeaders(response))) {
            try (InputStream ignored = response.body()) {
                throw new HttpStatusException(
                        "HTTP image source returned unsupported Content-Encoding; only identity is supported",
                        502,
                        null);
            }
        }
        return response;
    }

    private static boolean hasUnsupportedContentEncoding(HttpHeaders headers) {
        return headers.getOrEmpty(HttpHeaders.CONTENT_ENCODING).stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(",", -1)))
                .anyMatch(coding -> !coding.trim().equalsIgnoreCase("identity"));
    }

    /// Check the response status and throw if we have a non-success status code.
    ///
    /// Note that this method assumes that HTTP 304 was handled before calling it and thus treats
    /// a 304 as an error condition and maps it to HTTP 502.
    private void checkStatus(HttpResponse<InputStream> response, HttpHeaders responseHeaders, CacheResult status)
            throws IOException {
        if (response.statusCode() == 304 || response.statusCode() >= 400) {
            response.body().close();
            if (config.exposeResponseHeaders()) {
                responseHeaders.set(RESPONSE_HEADER, status.headerValue());
            } else {
                responseHeaders.remove(RESPONSE_HEADER);
            }
            // With no stored body a source 304 cannot satisfy an image load, nor authorize an IIIF 304.
            int code = response.statusCode() == 304 ? 502 : response.statusCode();
            throw new HttpStatusException("Failed to load image", code, null, responseHeaders);
        }
    }

    /// Trust a conditional 304 unless it explicitly contradicts the stored ETag.
    private static boolean validRevalidation(HttpHeaders previous, HttpHeaders response, HttpHeaders request) {
        if (request.containsHeader(HttpHeaders.IF_NONE_MATCH)) {
            String oldTag = previous.getETag(), newTag = response.getETag();
            return oldTag != null && (newTag == null || weakTag(oldTag).equals(weakTag(newTag)));
        }
        return request.containsHeader(HttpHeaders.IF_MODIFIED_SINCE);
    }

    /// Create a weak ETag validator from the existing ETag, if it's not already weak
    private static String weakTag(String value) {
        return value.startsWith("W/") ? value.substring(2) : value;
    }

    /// Create the set of HTTP request headers for the upstream request.
    ///
    /// Require identity encoding for cached and direct sources. Reject a conflicting resolver
    /// value rather than silently overwriting a header that may be part of a signed request.
    ///
    /// @param values original set of request headers provided by the resolver
    /// @return the updated HTTP request headers
    private static HttpHeaders requestHeaders(@Nullable Map<String, String> values) {
        HttpHeaders headers = new HttpHeaders();
        if (values != null) {
            values.forEach(headers::set);
        }
        String encoding = headers.getFirst(HttpHeaders.ACCEPT_ENCODING);
        if (encoding != null && !encoding.trim().equalsIgnoreCase("identity")) {
            throw new HttpStatusException(
                    "Resolver supplied unsupported Accept-Encoding; HTTP image sources require identity", 500, null);
        }
        if (encoding == null) {
            headers.set(HttpHeaders.ACCEPT_ENCODING, "identity");
        }
        return headers;
    }

    /// Pull response headers from the [HttpResponse]
    private static HttpHeaders responseHeaders(HttpResponse<?> response) {
        HttpHeaders headers = new HttpHeaders();
        if (response.headers() != null)
            response.headers().map().forEach((name, values) -> headers.put(name, List.copyOf(values)));
        return headers;
    }

    /// Create a copy of the existing [HttpHeaders]
    private static HttpHeaders copy(HttpHeaders headers) {
        HttpHeaders copy = new HttpHeaders();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return copy;
    }

    /// Obtain the length in bytes from the `Content-Length` HTTP header
    ///
    /// @return the length in bytes or -1 if no length could be decoded from the headers
    private static long contentLength(HttpHeaders headers) {
        List<String> lengths = headers.getOrEmpty(HttpHeaders.CONTENT_LENGTH);
        if (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]+")) {
            return -1;
        }
        try {
            return Long.parseLong(lengths.getFirst());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /// Reject new acquisitions. Existing acquisitions and leases may finish normally.
    ///
    /// This does not wait for callers to close their leases: the last acquisition or file lease
    /// releases the directory lock. Keeping ownership during that drain prevents a replacement
    /// instance from recovering files that an old request can still invalidate or delete.
    @Override
    public synchronized void close() throws IOException {
        closing = true;
        closeDiskIfIdle();
    }

    /// One request's access to a file or origin stream. Do not share it between requests.
    /// Close it when the request arena closes, after libvips has finished reading.
    /// Releasing a cached file takes the cache lock, then the store lock.
    public final class Lease implements HttpSourceLease {
        /// Cache entry associated with this lease. Can be replaced when a revalidation refreshes
        /// its metadata. `null` when the cache is bypassed for the access.
        private @Nullable CacheEntry entry;

        /// Upstream body of a bypassed response; `null` when the body comes from the cached file
        private final @Nullable InputStream direct;

        /// Response headers of a bypassed response; null when the cached entry holds them
        private final @Nullable HttpHeaders directHeaders;

        /// Diagnostic status for `X-Wolpi-Http-Source-Cache`
        private CacheResult result;

        /// True when `close()` has been called on this lease
        private boolean released;

        private Lease(
                @Nullable CacheEntry entry,
                @Nullable InputStream direct,
                @Nullable HttpHeaders directHeaders,
                CacheResult result) {
            this.entry = entry;
            this.direct = direct;
            this.directHeaders = directHeaders;
            this.result = result;
        }

        /// Get the HTTP headers that were used to obtain the direct or cached response
        public HttpHeaders responseHeaders() {
            if (entry == null) {
                assert directHeaders != null;
                return directHeaders;
            } else {
                return entry.headers;
            }
        }

        /// Get the value for the diagnostic cache status header
        public String cacheStatusHeaderValue() {
            return result.headerValue();
        }

        /// If the body can be safely reopened, not the case if the response is direct or
        /// the lease was closed
        public boolean canReopenBody() {
            return entry != null && !released;
        }

        /// Get the path on the disk for the cache entry, or null if the response is direct
        public @Nullable Path path() {
            return entry == null ? null : entry.diskBody.path;
        }

        /// Get an [InputStream] for the entry, will either be from live HTTP response or the cache
        /// file on disk
        public InputStream openStream() throws IOException {
            if (released) {
                throw new IOException("HTTP source lease already closed");
            }
            if (direct != null) {
                return direct;
            } else {
                assert entry != null;
                return Files.newInputStream(entry.diskBody.path);
            }
        }

        /// Get the cached image info, or null if the response is direct
        public @Nullable ImageInfo imageInfo() {
            return entry == null ? null : entry.imageInfo;
        }

        /// Set the [ImageInfo] on the cache entry, no effect if the response is direct
        public void cacheImageInfo(ImageInfo info) {
            if (entry != null)
                entry.imageInfo = new ImageInfo(
                        info.format(), info.nativeSize(), List.copyOf(info.sizes()), List.copyOf(info.tileSizes()));
        }

        /// Close this lease and mark it as released in the [DiskStore] (if cached) or close
        /// the [InputStream] on the direct [HttpResponse]
        @Override
        public void close() throws IOException {
            if (!released) {
                released = true;
                if (entry != null) {
                    synchronized (HttpSourceCache.this) {
                        assert disk != null;
                        disk.release(entry);
                        activeFileLeases--;
                        closeDiskIfIdle();
                    }
                }
                if (direct != null) {
                    direct.close();
                }
            }
        }
    }

    /// Keeps the directory locked until this request's cache work and cleanup have finished.
    private final class AcquisitionScope implements AutoCloseable {
        @Override
        public void close() throws IOException {
            synchronized (HttpSourceCache.this) {
                activeAcquisitions--;
                closeDiskIfIdle();
            }
        }
    }

    private static final class AcquisitionLock {
        final ReentrantLock lock = new ReentrantLock();
        int users; // Owner and waiting callers; guarded by HttpSourceCache.this.

        @Nullable CacheResult completedResult; // Guarded by lock; shared with waiting callers.
    }
}
