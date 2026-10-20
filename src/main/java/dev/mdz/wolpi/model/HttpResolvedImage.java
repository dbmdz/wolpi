package dev.mdz.wolpi.model;

import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// An HTTP source and the credentials/headers needed for its current acquisition.
/// HTTP content coding is unsupported: omit Accept-Encoding or supply identity.
///
/// cacheIdentity optionally replaces URL-and-header cache keying with a resolver-controlled,
/// deployment-wide identity. It must distinguish representation variants and access scopes.
/// Resolver validators still partition versions, and upstream cache policy still applies.
public record HttpResolvedImage(
        URI url,
        @Nullable Map<String, String> headers,
        @Nullable String cacheIdentity) implements ResolvedImage {
    public HttpResolvedImage {
        if (cacheIdentity != null && cacheIdentity.isBlank()) {
            throw new IllegalArgumentException("HTTP source cacheIdentity must not be blank");
        }
    }

    public HttpResolvedImage(URI url, @Nullable Map<String, String> headers) {
        this(url, headers, null);
    }
}
