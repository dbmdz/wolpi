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

    /// Include the URI, resolver validators, and source headers except conditional validators.
    static String create(URI uri, HttpHeaders headers, @Nullable CacheInfo version) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            add(hash, uri.toString());
            add(hash, version == null || version.eTag() == null ? "" : version.eTag());
            add(
                    hash,
                    version == null || version.lastModified() == null
                            ? ""
                            : version.lastModified().toString());
            addRequestHeaders(hash, headers);
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void addRequestHeaders(MessageDigest hash, HttpHeaders headers) {
        TreeMap<String, List<String>> canonical = new TreeMap<>();
        headers.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!VALIDATORS.contains(lower)) canonical.put(lower, values);
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
