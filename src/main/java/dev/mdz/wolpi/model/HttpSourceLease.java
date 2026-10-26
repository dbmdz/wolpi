package dev.mdz.wolpi.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/// Request-local access to an HTTP image body. Close only after lazy image decoding finishes.
///
/// The model layer owns this contract so that [ImageSource] can hold a lease without reaching into
/// image processing (which is forbidden by our architecture).
public interface HttpSourceLease extends AutoCloseable {
    /// Metadata for the acquired body, not headers for a new upstream request.
    HttpHeaders responseHeaders();

    /// Source cache diagnostic value, not an HTTP status code.
    String cacheStatusHeaderValue();

    /// Whether this request can open the body again. Does not imply freshness for new requests.
    boolean canReopenBody();

    /// Pinned local body, or null for a direct origin stream. Valid only until this lease closes.
    @Nullable Path path();

    /// Open an independent file stream, or return the same one-shot origin stream on a direct lease.
    /// Callers close streams they open and must finish reading before closing the lease.
    InputStream openStream() throws IOException;

    @Nullable ImageInfo imageInfo();

    /// Publish extracted metadata for other readers of the same stored body.
    void cacheImageInfo(ImageInfo info);

    @Override
    void close() throws IOException;
}
