package dev.mdz.wolpi.extension;

import static java.nio.file.Files.isExecutable;
import static java.nio.file.Files.isReadable;
import static java.nio.file.Files.isWritable;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// Very basic Truffle filesystem that can resolve `wolpi:` paths to JS sources inside
/// the JAR/project directory, and all other paths to the default filesystem provider.
///
/// This largely just proxies to the various `Files` methods for most paths.
/// `wolpi:` paths are resolved to the `js/` directory in the JAR or in the `target`
/// directory (when running in exploded form). Additionally, we support query parameters on
/// the filename component of paths, which can be used to implement cache busting (currently used
/// for live reloading of JS packages using imports)
public class ESMFileSystem implements org.graalvm.polyglot.io.FileSystem {

    private static final String WOLPI_INTERNAL_SCHEME = "wolpi:";
    private static final Pattern NESTED_JAR_PATTERN =
            Pattern.compile("^jar:nested:(?<outerJar>.+\\.jar)/!(?<innerParent>.+)!.+$");
    private final FileSystem defaultFs;
    private final @Nullable Path wolpiPathPrefix;
    private final @Nullable FileSystem jarFs;

    public ESMFileSystem() {
        this(resolveCodeSourceLocation());
    }

    // e.g. jar:nested:/local/applications/wolpi-${version}.jar/!BOOT-INF/classes/!/js/fetch.mjs
    ESMFileSystem(String jarLocation) {
        this.defaultFs = java.nio.file.FileSystems.getDefault();
        // Spring Boot builds a nested JAR by default, which has a special URL format that we need to handle.
        Matcher m = NESTED_JAR_PATTERN.matcher(jarLocation);
        try {
            if (m.matches()) {
                Path outerJarPath = Path.of(m.group("outerJar")).toAbsolutePath();
                this.jarFs = FileSystems.newFileSystem(outerJarPath);
                if (m.group("innerParent").endsWith(".jar")) {
                    throw new RuntimeException(
                            "Inner JARs in nested JARs are not supported: %s".formatted(m.group("innerParent")));
                }
                this.wolpiPathPrefix = jarFs.getPath(m.group("innerParent"));
            } else if (jarLocation.endsWith(".jar")) {
                this.jarFs = FileSystems.newFileSystem(
                        Path.of(jarLocation.replace("file:", "")).toAbsolutePath());
                this.wolpiPathPrefix = null;
            } else {
                this.jarFs = null;
                this.wolpiPathPrefix =
                        defaultFs.getPath(jarLocation.replace("file:", "")).toAbsolutePath();
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to initialize ESMFileSystem with jar location: %s".formatted(jarLocation), e);
        }
    }

    @Override
    public @Nullable Path parsePath(URI uri) {
        return parsePath(uri.toString());
    }

    @Override
    public @Nullable Path parsePath(String path) {
        if (path.startsWith(WOLPI_INTERNAL_SCHEME)) {
            var resourcePath = path.substring(6);
            if (wolpiPathPrefix != null) {
                return wolpiPathPrefix.resolve("js").resolve(resourcePath + ".mjs");
            } else if (jarFs != null) {
                return jarFs.getPath("js", resourcePath + ".mjs");
            }
        } else if (path.startsWith("file:")) {
            try {
                return Path.of(new URI(path));
            } catch (Exception e) {
                return null;
            }
        }
        // We must not strip the query from the path here, since Graal uses the canonical path as
        // key for caching Luckily this doesn't break anything (-:
        return defaultFs.getPath(path);
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions) throws IOException {
        if (linkOptions.length > 0) {
            path = path.toRealPath(linkOptions);
        }

        path = stripQuery(path);

        for (AccessMode mode : modes) {
            switch (mode) {
                case AccessMode.READ -> {
                    if (!isReadable(path)) {
                        throw new IOException("Path is not readable: %s".formatted(path));
                    }
                }
                case AccessMode.WRITE -> {
                    if (!isWritable(path)) {
                        throw new IOException("Path is not writable: %s".formatted(path));
                    }
                }
                case AccessMode.EXECUTE -> {
                    if (!isExecutable(path)) {
                        throw new IOException("Path is not executable: %s".formatted(path));
                    }
                }
            }
        }
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        Files.createDirectory(stripQuery(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.delete(stripQuery(path));
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
            throws IOException {
        return Files.newByteChannel(stripQuery(path), options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter) throws IOException {
        return Files.newDirectoryStream(stripQuery(dir), filter);
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return path.toAbsolutePath();
    }

    @Override
    public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
        return path.toRealPath(linkOptions);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return Files.readAttributes(stripQuery(path), attributes, options);
    }

    /// Strip any query parameters from the path's filename
    ///
    /// @param path The path to strip, may contain query parameters
    /// @return The path without query parameters in the filename
    private Path stripQuery(Path path) {
        String lastPath = path.getFileName().toString();
        if (lastPath.contains("?")) {
            return path.resolveSibling(lastPath.substring(0, lastPath.indexOf('?')));
        }
        return path;
    }

    private static String resolveCodeSourceLocation() {
        return ESMFileSystem.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toString();
    }
}
