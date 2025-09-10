package dev.mdz.wolpi.extension.mapping;

import app.photofox.vipsffm.VCustomSource;
import app.photofox.vipsffm.VCustomSource.ReadCallback;
import app.photofox.vipsffm.VCustomSource.SeekCallback;
import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import dev.mdz.wolpi.model.BinaryResolvedImage;
import dev.mdz.wolpi.model.CustomSourceResolvedImage;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.ResolvedImage;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Set;
import org.graalvm.polyglot.Value;

public class ResolvedImageMapper {
  private static final Set<String> RESOLVED_IMAGE_MEMBERS = Set.of("url", "rawData", "path");

  private ResolvedImageMapper() {}

  public static boolean canMap(Value val) {
    return RESOLVED_IMAGE_MEMBERS.stream()
        .anyMatch(m -> PolyglotHelpers.hasDictOrObjectMember(m, val, true));
  }

  public static ResolvedImage map(Value val) {
    if (PolyglotHelpers.hasDictOrObjectMember("onRead", val, true)
        && PolyglotHelpers.hasDictOrObjectMember("onSeek", val, true)) {
      var seekFn = PolyglotHelpers.getDictOrObjectMember("onSeek", val, true);
      if (seekFn == null || !seekFn.canExecute()) {
        throw new IllegalArgumentException(
            "Invalid onSeek member in custom source, is not executable");
      }
      var readFn = PolyglotHelpers.getDictOrObjectMember("onRead", val, true);
      if (readFn == null || !readFn.canExecute()) {
        throw new IllegalArgumentException(
            "Invalid onRead member in custom source, is not executable");
      }

      ReadCallback readCb =
          (memorySegment, length) -> {
            var readResult = readFn.execute((int) length);
            if (readResult == null || readResult.isNull()) {
              return 0;
            }
            byte[] data = readResult.as(byte[].class);
            VarHandle byteArrayHandle =
                MethodHandles.byteArrayViewVarHandle(
                    byte.class, java.nio.ByteOrder.nativeOrder());
            byteArrayHandle.set(memorySegment, data);
            return data.length;
          };
      SeekCallback seekCb =
          (offset, whence) -> seekFn.execute(offset, whence.getValue()).asLong();
      return new CustomSourceResolvedImage(
          vipsArena -> new VCustomSource(vipsArena, readCb, seekCb));
    } else if (PolyglotHelpers.hasDictOrObjectMember("path", val)) {
      return val.as(FilesystemResolvedImage.class);
    } else if (PolyglotHelpers.hasDictOrObjectMember("rawData", val, true)) {
      return val.as(BinaryResolvedImage.class);
    } else if (PolyglotHelpers.hasDictOrObjectMember("url", val)) {
      return val.as(HttpResolvedImage.class);
    } else {
      throw new IllegalArgumentException(
          "Cannot map polyglot value [%s] to ResolvedImage".formatted(val.toString()));
    }
  }

}
