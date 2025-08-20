package dev.mdz.iiif.wolpi.model.image;

import app.photofox.vipsffm.VCustomSource;
import app.photofox.vipsffm.VCustomSource.ReadCallback;
import app.photofox.vipsffm.VCustomSource.SeekCallback;
import java.lang.foreign.Arena;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;

public sealed interface ResolvedImage
    permits FilesystemResolvedImage,
        HttpResolvedImage,
        BinaryResolvedImage,
        CustomSourceResolvedImage {
  static @Nullable ResolvedImage from(Arena vipsArena, @Nullable Value polyglotValue) {
    if (polyglotValue == null) {
      return null;
    }
    if (polyglotValue.hasMember("httpUrl")) {
      //noinspection unchecked
      return new HttpResolvedImage(
          polyglotValue.getMember("httpUrl").as(URI.class),
          polyglotValue.hasMember("headers")
              ? Collections.unmodifiableMap(
                  (Map<String, String>) polyglotValue.getMember("headers").as(Map.class))
              : null,
          polyglotValue.getMember("supportsByteRanges").asBoolean());
    } else if (polyglotValue.hasMember("rawData") && polyglotValue.hasMember("mimeType")) {
      return new BinaryResolvedImage(
          polyglotValue.getMember("rawData").as(byte[].class),
          polyglotValue.getMember("mimeType").as(String.class));
    } else if (polyglotValue.hasMember("onRead") && polyglotValue.hasMember("onWrite")) {
      ReadCallback readCb =
          (memorySegment, length) -> {
            // TODO: Call read on on the polyglot value
            // TODO: Write returned data to the memory segment
            throw new UnsupportedOperationException();
          };
      SeekCallback seekCb =
          (offset, whence) -> {
            // TODO: Call seek on the polyglot value
            throw new UnsupportedOperationException();
          };
      return new CustomSourceResolvedImage(new VCustomSource(vipsArena, readCb, seekCb));
    } else {
      return new FilesystemResolvedImage(Path.of(polyglotValue.getMember("path").as(String.class)));
    }
  }
}
