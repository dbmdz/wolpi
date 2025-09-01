package dev.mdz.iiif.wolpi.model.image;

import app.photofox.vipsffm.VCustomSource;
import app.photofox.vipsffm.VCustomSource.ReadCallback;
import app.photofox.vipsffm.VCustomSource.SeekCallback;
import dev.mdz.iiif.wolpi.util.PolyglotHelpers;
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

    if (PolyglotHelpers.hasDictOrObjectMember("url", polyglotValue)) {
      var url = PolyglotHelpers.getDictOrObjectMember("url", polyglotValue);
      assert url != null && !url.isNull();
      var supportsByteRanges = PolyglotHelpers.getDictOrObjectMember("supportsByteRanges", polyglotValue);

      //noinspection unchecked
      return new HttpResolvedImage(
          URI.create(url.asString()),
          PolyglotHelpers.hasDictOrObjectMember("headers", polyglotValue)
              ? Collections.unmodifiableMap(
                  (Map<String, String>) PolyglotHelpers.getDictOrObjectMember("headers", polyglotValue).as(Map.class))
              : null,
          supportsByteRanges != null && !supportsByteRanges.isNull() ? supportsByteRanges.asBoolean() : null);
    } else if (PolyglotHelpers.hasDictOrObjectMember("rawData", polyglotValue) && PolyglotHelpers.hasDictOrObjectMember("mimeType", polyglotValue)) {
      return new BinaryResolvedImage(PolyglotHelpers.getDictOrObjectMember("rawData", polyglotValue).as(byte[].class));
    } else if (PolyglotHelpers.hasDictOrObjectMember("onRead", polyglotValue) && PolyglotHelpers.hasDictOrObjectMember("onWrite", polyglotValue)) {
      ReadCallback readCb =
          (memorySegment, length) -> {
            // TODO: Call read on the polyglot value
            // TODO: Write returned data to the memory segment
            throw new UnsupportedOperationException();
          };
      SeekCallback seekCb =
          (offset, whence) -> {
            // TODO: Call seek on the polyglot value
            throw new UnsupportedOperationException();
          };
      return new CustomSourceResolvedImage(new VCustomSource(vipsArena, readCb, seekCb));
    } else if (PolyglotHelpers.hasDictOrObjectMember("path", polyglotValue)) {
      var pathValue = PolyglotHelpers.getDictOrObjectMember("path", polyglotValue);
      assert pathValue != null;
      return new FilesystemResolvedImage(Path.of(pathValue.asString()));
    } else {
      return null;
    }
  }
}
