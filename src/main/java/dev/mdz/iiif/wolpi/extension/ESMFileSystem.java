package dev.mdz.iiif.wolpi.extension;

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
import org.jspecify.annotations.Nullable;

/// Very basic Truffle filesystem that can resolve `wolpi:` paths to JS sources inside
/// the JAR/project directory, and all other paths to the default filesystem provider.
///
/// This largely just proxies to the various `Files` methods for most paths.
/// `wolpi:` paths are resolved to the `js/` directory in the JAR or in the `target`
/// directory (when running in exploded form)
public class ESMFileSystem implements org.graalvm.polyglot.io.FileSystem {

  private static final String WOLPI_INTERNAL_SCHEME = "wolpi:";
  private final FileSystem defaultFs;
  private final @Nullable Path wolpiPathPrefix;
  private final @Nullable FileSystem jarFs;

  public ESMFileSystem() {
    this.defaultFs = java.nio.file.FileSystems.getDefault();
    try {
      String jarLocation =
          getClass().getProtectionDomain().getCodeSource().getLocation().toString();
      Path absolutePath = Path.of(jarLocation.replace("file:", "")).toAbsolutePath();
      if (jarLocation.endsWith(".jar")) {
        this.jarFs = FileSystems.newFileSystem(absolutePath);
        this.wolpiPathPrefix = null;
      } else {
        this.jarFs = null;
        this.wolpiPathPrefix = absolutePath;
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
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
    }
    return defaultFs.getPath(path);
  }

  @Override
  public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... linkOptions)
      throws IOException {
    if (linkOptions.length > 0) {
      path = path.toRealPath(linkOptions);
    }

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
    Files.createDirectory(dir, attrs);
  }

  @Override
  public void delete(Path path) throws IOException {
    Files.delete(path);
  }

  @Override
  public SeekableByteChannel newByteChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    return Files.newByteChannel(path, options, attrs);
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter)
      throws IOException {
    return Files.newDirectoryStream(dir, filter);
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
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    return Files.readAttributes(path, attributes, options);
  }
}
