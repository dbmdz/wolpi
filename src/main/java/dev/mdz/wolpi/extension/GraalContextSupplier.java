package dev.mdz.wolpi.extension;

import app.photofox.vipsffm.VipsHelper;
import dev.mdz.wolpi.extension.mapping.RecordValueMapper;
import dev.mdz.wolpi.extension.mapping.ResolvedImageMapper;
import dev.mdz.wolpi.extension.model.ExtensionGuestContext;
import dev.mdz.wolpi.extension.model.ExtensionInfo;
import dev.mdz.wolpi.model.BinaryResolvedImage;
import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.HttpResolvedImage;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSize;
import dev.mdz.wolpi.model.ResolvedImage;
import dev.mdz.wolpi.model.SourceNotModified;
import dev.mdz.wolpi.model.TileSize;
import dev.mdz.wolpi.validation.model.ValidationResult.ValidationFailure;
import dev.mdz.wolpi.validation.model.ValidationResult.ValidationSuccess;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
    private static final List<Class<? extends Record>> MAPPED_RECORDS = List.of(
            CacheInfo.class,
            ExtensionInfo.class,
            ImageInfo.class,
            ImageSize.class,
            TileSize.class,
            FilesystemResolvedImage.class,
            HttpResolvedImage.class,
            BinaryResolvedImage.class,
            SourceNotModified.class,
            ValidationSuccess.class,
            ValidationFailure.class);

    private static final HostAccess hostAccess = buildHostAccess();

    private static Engine graalEngine = Engine.newBuilder("python", "js").build();

    private GraalContextSupplier() {}

    /// Configure host access for GraalVM polyglot contexts.
    ///
    /// The access mode is equivalent to [HostAccess#ALL], but with additional type mappings for
    /// the record types passed between Wolpi and the Extensions, as well as for some common Java
    /// types that we map from [String]s in the polyglot context.
    private static HostAccess buildHostAccess() {
        var builder = HostAccess.newBuilder(HostAccess.ALL)
                .denyAccess(MemorySegment.class) // Disallow direct FFM pointer access
                .denyAccess(VipsHelper.class); // Disallow direct access to vips-ffm internals
        for (var r : MAPPED_RECORDS) {
            //noinspection unchecked
            Class<Record> recordClass = (Class<Record>) r;
            RecordValueMapper<Record> converter = new RecordValueMapper<>(recordClass);
            builder.targetTypeMapping(Value.class, recordClass, converter::accepts, converter::convert);
        }
        builder.targetTypeMapping(
                Value.class, ResolvedImage.class, ResolvedImageMapper::canMap, ResolvedImageMapper::map);
        builder.targetTypeMapping(Value.class, URI.class, Value::isString, v -> URI.create(v.asString()));
        builder.targetTypeMapping(Value.class, Instant.class, Value::isString, v -> Instant.parse(v.asString()));
        builder.targetTypeMapping(Value.class, Path.class, Value::isString, v -> Path.of(v.asString()));
        // For `TypedArray` objects in JS we need to access the `ArrayBuffer` inside the `TypedArray` to
        // get the actual bytes
        builder.targetTypeMapping(Value.class, byte[].class, v -> v.hasMember("buffer"), v -> v.getMember("buffer")
                .as(byte[].class));
        return builder.build();
    }

    /// Construct a new GraalJS JavaScript context for executing extension code.
    ///
    /// @param wolpiCtx optional Wolpi context to make available to the extension as a global variable
    ///                 `wolpi`, or `null` if it is not available for this context
    /// @return the new GraalVM polyglot context, initialized for use by Wolpi extensions
    public static Context getJsContext(@Nullable ExtensionGuestContext wolpiCtx) {
        var ctx = Context.newBuilder("js")
                .allowHostAccess(hostAccess)
                .allowHostClassLookup(c -> true)
                .engine(graalEngine)
                .allowIO(IOAccess.newBuilder().fileSystem(new ESMFileSystem()).build())
                .useSystemExit(false) // Disallow `exit()` calls from JS extensions
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
    public static Context getPythonContext(@Nullable Path venvPath, @Nullable ExtensionGuestContext wolpiCtx) {
        var builder = Context.newBuilder("python")
                .engine(graalEngine)
                .allowHostAccess(hostAccess)
                .allowHostClassLookup(c -> true)
                .allowIO(IOAccess.ALL)
                .useSystemExit(false) // Disallow `exit()` calls from Python extensions
                .allowCreateThread(true)
                .allowExperimentalOptions(true)
                .option("python.IsolateNativeModules", "true");
        if (venvPath != null) {
            Path pythonExecutable = Stream.of("graalpy", "python3", "python")
                    .map(cmd -> venvPath.resolve("bin", cmd))
                    .filter(p -> Files.exists(p) && Files.isExecutable(p))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Unable to find python executable in virtual environment: " + venvPath.toAbsolutePath()));
            builder.option(
                            "python.Executable",
                            pythonExecutable.toAbsolutePath().toString())
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
                                "python.PythonPath",
                                p.resolve("site-packages").toAbsolutePath().toString());
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

    /// **Internal API only** - Reset the GraalVM engine, closing all existing contexts.
    ///
    /// There should never be a need to call this method outside test classes that have a per-class
    /// lifecycle. The caches in the Engine are absolutely vital for performance and should not be
    /// discarded during normal operation.
    public static void resetEngine() {
        graalEngine.close();
        graalEngine = Engine.newBuilder("python", "js").build();
    }
}
