package dev.mdz.wolpi.extension;

import app.photofox.vipsffm.VCustomSource;
import app.photofox.vipsffm.VCustomSource.ReadCallback;
import app.photofox.vipsffm.VCustomSource.SeekCallback;
import dev.mdz.wolpi.extension.model.ExtensionContext;
import dev.mdz.wolpi.extension.model.ExtensionInfo;
import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import dev.mdz.wolpi.extension.util.RecordValueMapper;
import dev.mdz.wolpi.model.BinaryResolvedImage;
import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.CustomSourceResolvedImage;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSize;
import dev.mdz.wolpi.model.ResolvedImage;
import dev.mdz.wolpi.model.TileSize;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.jspecify.annotations.Nullable;

/// Supplies GraalVM polyglot contexts for executing extension code and configures them with the
/// appropriate type mappings.
public class GraalContextSupplier {
  private static final List<Class<? extends Record>> MAPPED_RECORDS =
      List.of(
          CacheInfo.class,
          ExtensionInfo.class,
          ImageInfo.class,
          ImageSize.class,
          TileSize.class,
          FilesystemResolvedImage.class,
          HttpResolvedImage.class,
          BinaryResolvedImage.class);
  private static final Set<String> RESOLVED_IMAGE_MEMBERS = Set.of("url", "rawData", "path");

  private static final Engine graalEngine = Engine.newBuilder("python", "js").build();
  private static final HostAccess hostAccess = buildHostAccess();

  private GraalContextSupplier() {}

  /// Configure host access for GraalVM polyglot contexts.
  ///
  /// The access mode is equivalent to [HostAccess#ALL], but with additional type mappings for
  /// the record types passed between Wolpi and the Extensions, as well as for some common Java
  /// types that we map from [String]s in the polyglot context.
  private static HostAccess buildHostAccess() {
    var builder = HostAccess.newBuilder(HostAccess.ALL);
    for (var r : MAPPED_RECORDS) {
      //noinspection unchecked
      Class<Record> recordClass = (Class<Record>) r;
      RecordValueMapper<Record> converter = new RecordValueMapper<>(recordClass);
      builder.targetTypeMapping(Value.class, recordClass, converter::accepts, converter::convert);
    }
    builder.targetTypeMapping(
        Value.class,
        ResolvedImage.class,
        v ->
            RESOLVED_IMAGE_MEMBERS.stream()
                .anyMatch(m -> PolyglotHelpers.hasDictOrObjectMember(m, v, true)),
        v -> {
          if (PolyglotHelpers.hasDictOrObjectMember("onRead", v, true)
              && PolyglotHelpers.hasDictOrObjectMember("onSeek", v, true)) {
            var seekFn = PolyglotHelpers.getDictOrObjectMember("onSeek", v, true);
            if (seekFn == null || !seekFn.canExecute()) {
              throw new IllegalArgumentException(
                  "Invalid onSeek member in custom source, is not executable");
            }
            var readFn = PolyglotHelpers.getDictOrObjectMember("onRead", v, true);
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
          } else if (PolyglotHelpers.hasDictOrObjectMember("path", v)) {
            return v.as(FilesystemResolvedImage.class);
          } else if (PolyglotHelpers.hasDictOrObjectMember("rawData", v, true)) {
            return v.as(BinaryResolvedImage.class);
          } else if (PolyglotHelpers.hasDictOrObjectMember("url", v)) {
            return v.as(HttpResolvedImage.class);
          } else {
            throw new IllegalArgumentException(
                "Cannot map polyglot value [%s] to ResolvedImage".formatted(v.toString()));
          }
        });
    builder.targetTypeMapping(
        Value.class, URI.class, Value::isString, v -> URI.create(v.asString()));
    builder.targetTypeMapping(
        Value.class, Instant.class, Value::isString, v -> Instant.parse(v.asString()));
    builder.targetTypeMapping(Value.class, Path.class, Value::isString, v -> Path.of(v.asString()));
    builder.targetTypeMapping(
        Value.class,
        byte[].class,
        v -> v.hasMember("buffer"),
        v -> v.getMember("buffer").as(byte[].class));
    return builder.build();
  }

  /// Construct a new GraalJS JavaScript context for executing extension code.
  ///
  /// @param wolpiCtx optional Wolpi context to make available to the extension as a global variable
  ///                 `wolpi`, or `null` if it is not available for this context
  /// @return the new GraalVM polyglot context, initialized for use by Wolpi extensions
  public static Context getJsContext(@Nullable ExtensionContext wolpiCtx) {
    var ctx =
        Context.newBuilder("js")
            .allowHostAccess(hostAccess)
            .engine(graalEngine)
            .allowIO(IOAccess.newBuilder().fileSystem(new ESMFileSystem()).build())
            .option("js.esm-eval-returns-exports", "true")
            .build();
    if (wolpiCtx != null) {
      ctx.getBindings("js").putMember("wolpi", wolpiCtx.forJS());
    }
    return ctx;
  }

  /// Build a new GraalPy Python context for executing extension code.
  ///
  /// @param venvPath   optional path to a Python virtual environment to use, or `null` if no
  ///                   virtual environment should be used
  /// @param wolpiCtx   optional Wolpi context to make available to the extension as a global
  ///                   variable `wolpi`, or `null` if it is not available for this context
  /// @return the new GraalVM polyglot context, initialized for use by Wolpi extensions
  public static Context getPythonContext(
      @Nullable Path venvPath, @Nullable ExtensionContext wolpiCtx) {
    var builder =
        Context.newBuilder("python")
            .engine(graalEngine)
            .allowHostAccess(hostAccess)
            .allowIO(IOAccess.ALL)
            .allowExperimentalOptions(true)
            .option("python.IsolateNativeModules", "true");
    if (venvPath != null) {
      Path pythonExecutable =
          Stream.of("graalpy", "python3", "python")
              .map(cmd -> venvPath.resolve("bin", cmd))
              .filter(p -> Files.exists(p) && Files.isExecutable(p))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Unable to find python executable in virtual environment: "
                              + venvPath.toAbsolutePath()));
      builder
          .option("python.Executable", pythonExecutable.toAbsolutePath().toString())
          .option("python.ForceImportSite", "true");

      // FIXME: It **should** be enough to specify the above two options for GraalPy to pick up the
      //        virtual environment correctly, but it doesn't. As a workaround, we manually set
      //        the PythonPath to the "pythonX.Y" directory in the "lib" directory of the venv.
      //        This is a bit hacky, but it works for now. It seems there will be changes in GraalPy
      //        25 that change how venvs work, revisit this then.
      try (var ds = Files.newDirectoryStream(venvPath.resolve("lib"))) {
        for (Path p : ds) {
          if (Files.isDirectory(p) && p.getFileName().toString().startsWith("python")) {
            builder.option(
                "python.PythonPath", p.resolve("site-packages").toAbsolutePath().toString());
            break;
          }
        }
      } catch (IOException e) {
        throw new IllegalStateException(
            "Unable to read lib directory of virtual environment: " + venvPath.toAbsolutePath(), e);
      }
    }
    var ctx = builder.build();

    if (wolpiCtx != null) {
      // Providing a global "wolpi" variable to Python extensions is a bit tricky, as Python doesn't
      // have a user writable truly global scope. We work around this by injecting it into the
      // language's `builtins` module, which defines the stdlib-provided global variables.
      var builtins = ctx.eval("python", "import builtins; builtins");
      builtins.putMember("wolpi", wolpiCtx);
    }

    return ctx;
  }
}
