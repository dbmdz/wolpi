package dev.mdz.wolpi.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

public record CacheInfo(@Nullable String eTag, @Nullable Instant lastModified) {
    /// Build a new [CacheInfo] object based on the filesystem metadata of a [Path] object
    public static CacheInfo fromPath(Path path) throws IOException {
        BasicFileAttributes attribs;
        attribs = Files.getFileAttributeView(path, BasicFileAttributeView.class).readAttributes();
        Instant lastModified = attribs.lastModifiedTime().toInstant();
        String eTag;
        try {
            var eTagSource =
                    "%s:%d:%d".formatted(path.toAbsolutePath().toString(), lastModified.toEpochMilli(), attribs.size());
            eTag = HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(eTagSource.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(
                    "Could not build ETag for file since JVM does not support SHA-256 hashing, this should not happen!",
                    e);
        }
        return new CacheInfo(eTag, lastModified);
    }
}
