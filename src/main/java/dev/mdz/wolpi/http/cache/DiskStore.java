package dev.mdz.wolpi.http.cache;

import dev.mdz.wolpi.http.cache.CacheEntry.DiskBody;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.json.JsonMapper;

/// Stores complete HTTP responses as files, within a limit on body bytes.
/// Metadata and filesystem overhead do not count towards that limit.
///
/// Synchronized methods protect the entry list, space counters, and file pins. If the caller also
/// needs the [HttpSourceCache] lock, it must take that lock first. Never acquire cache or per-key
/// locks from inside this store.
///
/// The directory's file lock prevents another Wolpi instance from using the same files.
/// Only close the store when no request can still read, write, or delete its files.
final class DiskStore implements AutoCloseable {
    private static final int MAX_ENTRIES = 10_000;

    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final JsonMapper mapper = new JsonMapper();
    private final Path directory;
    private final long limitBytes;
    private final FileChannel lockChannel;
    private final FileLock lock;

    /// Entries in last-access order, least recently used first.
    private final LinkedHashMap<String, CacheEntry> entries = new LinkedHashMap<>(16, 0.75f, true);

    /// Bytes used by published files. Still count old files in use and files we couldn't delete.
    private long retainedBodyBytes;

    /// Bytes set aside for downloads not yet published or cleaned up.
    private long reservedBodyBytes;

    /// Number of cache entries that are currently reserved
    private int reservedEntries;

    private boolean closed;

    DiskStore(Path directory, long limitBytes) throws IOException {
        this.directory = directory;
        this.limitBytes = limitBytes;
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(
                directory.resolve(".instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = acquireCacheLock();

        try {
            recover();
        } catch (IOException | RuntimeException e) {
            lock.release();
            lockChannel.close();
            throw e;
        }
    }

    /// Acquire a lock on the cache directory to ensure a single Wolpi instance is its sole user.
    ///
    /// Held for the lifetime of the instance.
    private FileLock acquireCacheLock() throws IOException {
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
        } catch (OverlappingFileLockException | IOException e) {
            lockChannel.close();
            throw new IOException("HTTP source cache directory is already in use or cannot be locked", e);
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("HTTP source cache directory is already in use");
        }
        return acquired;
    }

    /// Recover the cache metadata state from the on-disk cache
    private void recover() throws IOException {
        List<CacheEntry> recovered = new ArrayList<>();
        Set<Path> retained = new HashSet<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json"))
                    .toList()) {
                try {
                    Metadata meta = mapper.readValue(path.toFile(), Metadata.class);
                    if (!path.getFileName().toString().equals(meta.key + ".json")
                            || !meta.body.matches(meta.key + "-[a-f0-9-]{36}\\.body")
                            || meta.length <= 0
                            || meta.length > limitBytes
                            || meta.validatedAt == null
                            || meta.headers == null
                            || meta.initialAgeMillis < 0) {
                        throw new IOException("Invalid HTTP source cache metadata");
                    }
                    Path body = directory.resolve(meta.body);
                    if (Files.isSymbolicLink(body) || !Files.isRegularFile(body) || Files.size(body) != meta.length) {
                        throw new IOException("Incomplete HTTP source cache body");
                    }
                    HttpHeaders headers = new HttpHeaders();
                    meta.headers.forEach(headers::put);
                    CacheEntry entry = new CacheEntry(
                            meta.key,
                            headers,
                            meta.validatedAt,
                            Duration.ofMillis(meta.initialAgeMillis),
                            new DiskBody(body, meta.length));
                    recovered.add(entry);
                    retained.add(path);
                    retained.add(body);
                } catch (RuntimeException | IOException e) {
                    log.warn("Discarding unreadable HTTP source cache metadata {}", path.getFileName());
                    Files.deleteIfExists(path);
                }
            }
        }
        // Remove unreferenced body, download and metadata files, leaving unrelated user files untouched.
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!retained.contains(path)
                        && (name.matches("[a-f0-9]{64}-[a-f0-9-]{36}\\.body")
                                || name.startsWith(".download-")
                                || name.startsWith(".metadata-"))) {
                    Files.deleteIfExists(path);
                }
            }
        }

        recovered.sort(Comparator.comparing(entry -> entry.validatedAt));
        for (CacheEntry entry : recovered) {
            entries.put(entry.key, entry);
            retainedBodyBytes += entry.diskBody.length;
        }

        evictFor(0);
    }

    /// Find an indexed entry. Bodies are immutable and validated during recovery and publication.
    synchronized @Nullable CacheEntry find(String key) {
        return closed ? null : entries.get(key);
    }

    /// Keep this entry's file until release(). The caller must hold the HttpSourceCache lock.
    synchronized void pin(CacheEntry entry) {
        entry.diskBody.pin();
    }

    /// Release one reader. Delete the file if it left the cache and no reader still needs it.
    synchronized void release(CacheEntry entry) {
        if (entry.diskBody.unpin()) {
            deleteBody(entry.diskBody);
        }
    }

    /// Try to reserve space in the cache and evict if necessary
    synchronized @Nullable Reservation reserve(long length) throws IOException {
        if (closed || length > limitBytes || !evictFor(length)) {
            return null;
        }
        Path temporary = Files.createTempFile(directory, ".download-", ".part");
        reservedBodyBytes += length;
        reservedEntries++;
        return new Reservation(temporary, length);
    }

    /// Evict least-recently-used unpinned entries to make room for another reservation.
    ///
    /// @return whether the byte budget and entry limit permit `additional` body bytes
    private boolean evictFor(long additional) {
        // The access-ordered index visits least-recently-used entries first; pinned bodies stay put.
        var iterator = entries.entrySet().iterator();
        while ((retainedBodyBytes + reservedBodyBytes > limitBytes - additional
                        || entries.size() + reservedEntries >= MAX_ENTRIES)
                && iterator.hasNext()) {
            CacheEntry entry = iterator.next().getValue();
            if (entry.diskBody.pinned()) {
                continue;
            }
            // Delete metadata first so a crash cannot resurrect an evicted entry.
            if (deleteMetadata(entry.key)) {
                iterator.remove();
                retire(entry.diskBody);
            }
        }
        return retainedBodyBytes + reservedBodyBytes <= limitBytes - additional
                && entries.size() + reservedEntries < MAX_ENTRIES;
    }

    /// Make a completed download available to readers.
    /// Use the reservation in try-with-resources so a failed publish deletes the file and frees space.
    synchronized CacheEntry publish(Reservation reservation, CacheEntry.Pending pending) throws IOException {
        if (closed || Files.size(reservation.path) != reservation.length) {
            throw new IOException("Incomplete HTTP source cache download");
        }
        Path bodyPath = directory.resolve(pending.key() + "-" + UUID.randomUUID() + ".body");
        Files.move(reservation.path, bodyPath, StandardCopyOption.ATOMIC_MOVE);
        // If anything below fails, reservation.close() must delete the renamed file.
        reservation.path = bodyPath;
        CacheEntry stored = new CacheEntry(
                pending.key(),
                pending.headers(),
                pending.validatedAt(),
                pending.initialAge(),
                new DiskBody(bodyPath, reservation.length));
        writeMetadata(stored);

        // Retire the old body version if there exists one for the key
        CacheEntry previous = entries.put(stored.key, stored);
        if (previous != null) {
            retire(previous.diskBody);
        }
        reservedBodyBytes -= reservation.length;
        reservedEntries--;
        retainedBodyBytes += stored.diskBody.length;
        reservation.finished = true; // The file is now cached; close() must leave it alone.
        return stored;
    }

    /// Write updated metadata for the same file. Return false on failure without removing the entry;
    /// the caller decides whether to remove it.
    synchronized boolean refresh(CacheEntry previous, CacheEntry refreshed) {
        if (closed || entries.get(previous.key) != previous || previous.diskBody.retired()) {
            return false;
        }
        try {
            writeMetadata(refreshed);
            entries.put(refreshed.key, refreshed);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not refresh HTTP source cache metadata", e);
            return false;
        }
    }

    /// Remove an entry from the cache, deleting both metadata and bodies
    synchronized void remove(String key) {
        CacheEntry entry = entries.remove(key);
        if (entry != null) {
            deleteMetadata(key);
            retire(entry.diskBody);
        }
    }

    /// Delete the metadata file for a cache entry.
    private boolean deleteMetadata(String key) {
        try {
            Files.deleteIfExists(directory.resolve(key + ".json"));
            return true;
        } catch (IOException e) {
            log.warn("Could not remove HTTP source cache metadata", e);
            return false;
        }
    }

    /// If nobody has a pin on the [DiskBody], delete it from the cache
    private void retire(DiskBody body) {
        if (body.retire()) {
            deleteBody(body);
        }
    }

    /// Delete the body file from disk
    private void deleteBody(DiskBody body) {
        try {
            Files.deleteIfExists(body.path);
            retainedBodyBytes -= body.length;
        } catch (IOException e) {
            // Account undeleted data until restart recovery, rather than exceeding the limit.
            log.warn("Could not remove retired HTTP source cache body", e);
        }
    }

    /// Write the metadata of the [CacheEntry] to disk as JSON
    private void writeMetadata(CacheEntry entry) throws IOException {
        Path temporary = Files.createTempFile(directory, ".metadata-", ".part");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        entry.headers.forEach((name, values) -> headers.put(name, List.copyOf(values)));
        try {
            mapper.writeValue(
                    temporary.toFile(),
                    new Metadata(
                            entry.key,
                            entry.diskBody.path.getFileName().toString(),
                            entry.diskBody.length,
                            headers,
                            entry.validatedAt,
                            entry.initialAge.toMillis()));
            Files.move(
                    temporary,
                    directory.resolve(entry.key + ".json"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                lock.release();
            } finally {
                lockChannel.close();
            }
        }
    }

    /// A download file and the disk space set aside for it. Use in try-with-resources.
    /// If publish() fails, close() deletes the file, even if it was renamed during publishing.
    /// If publish() succeeds, close() does nothing: the store now manages the file and its space.
    final class Reservation implements AutoCloseable {
        Path path;
        final long length;

        /// True after publishing or cleanup finishes. Read and write only under the store lock.
        private boolean finished;

        Reservation(Path path, long length) {
            this.path = path;
            this.length = length;
        }

        /// Delete an unpublished file and free its reserved space. Only take the store lock here:
        /// the surrounding acquire() has not finished, so shutdown cannot unlock the directory yet.
        @Override
        public void close() throws IOException {
            synchronized (DiskStore.this) {
                if (!finished) {
                    Files.deleteIfExists(path);
                    reservedBodyBytes -= length;
                    reservedEntries--;
                    finished = true;
                }
            }
        }
    }

    /// [CacheEntry] metadata DTO for serialization to disk
    record Metadata(
            String key,
            String body,
            long length,
            Map<String, List<String>> headers,
            Instant validatedAt,
            long initialAgeMillis) {}
}
