package dev.mdz.wolpi.http.cache;

import dev.mdz.wolpi.model.CacheInfo;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

final class CacheKey {
    static final Set<String> VALIDATORS = Set.of("if-none-match", "if-modified-since");

    private CacheKey() {}

    static String create(URI uri, HttpHeaders headers, @Nullable CacheInfo version) {
        return create(uri, headers, version, null);
    }

    /// Default to the URI and all source request headers except conditional validators. An explicit
    /// identity replaces both: the trusted resolver owns representation and access-scope isolation.
    /// Resolver validators partition versions in either mode. No raw identity is persisted.
    static String create(URI uri, HttpHeaders headers, @Nullable CacheInfo version, @Nullable String cacheIdentity) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            if (cacheIdentity == null) {
                // Preserve the existing default key format for entries already on disk.
                add(hash, uri.toString());
            } else {
                // A negative length cannot begin a default key. Keep the two identity namespaces disjoint.
                hash.update(ByteBuffer.allocate(4).putInt(-1).array());
                add(hash, cacheIdentity);
            }
            // Resolver validators use their own namespace; do not compare them to origin ETags.
            add(hash, version == null || version.eTag() == null ? "" : version.eTag());
            add(
                    hash,
                    version == null || version.lastModified() == null
                            ? ""
                            : version.lastModified().toString());
            if (cacheIdentity == null) {
                addRequestHeaders(hash, headers);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void addRequestHeaders(MessageDigest hash, HttpHeaders headers) {
        TreeMap<String, List<String>> canonical = new TreeMap<>();
        headers.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!VALIDATORS.contains(lower)) {
                canonical.put(lower, values);
            }
        });
        canonical.forEach((name, values) -> {
            add(hash, name);
            hash.update(ByteBuffer.allocate(4).putInt(values.size()).array());
            values.forEach(value -> add(hash, value));
        });
    }

    private static void add(MessageDigest hash, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
    }
}
