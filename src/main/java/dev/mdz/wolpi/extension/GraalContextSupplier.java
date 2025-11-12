package dev.mdz.wolpi.extension;

import app.photofox.vipsffm.VipsHelper;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.config.WolpiConfig.ExtensionDebugConfig;
import dev.mdz.wolpi.extension.mapping.RecordValueMapper;
import dev.mdz.wolpi.extension.mapping.ResolvedImageMapper;
import dev.mdz.wolpi.extension.model.ExtensionGuestContext;
import dev.mdz.wolpi.extension.model.ExtensionInfo;
import dev.mdz.wolpi.extension.model.Language;
import dev.mdz.wolpi.extension.util.PolyglotHelpers;
import dev.mdz.wolpi.iiif.model.IIIFQuality;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.model.BinaryResolvedImage;
import dev.mdz.wolpi.model.CacheInfo;
import dev.mdz.wolpi.model.EncodedImage;
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
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/// Supplies GraalVM polyglot contexts for executing extension code and configures them with the
/// appropriate type mappings.
@Component
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
            ValidationFailure.class,
            EncodedImage.class);
    private static final Pattern URI_PATTERN = Pattern.compile("^https?://.*$");

    private final HostAccess hostAccess;

    private Engine graalEngine;

    public GraalContextSupplier(@Nullable WolpiConfig config) {
        this.hostAccess = buildHostAccess();
        var engineBuilder = Engine.newBuilder("python", "js").allowExperimentalOptions(true);
        if (config != null) {
            ExtensionDebugConfig debugCfg = config.extensionDebug();
            if (debugCfg != null && debugCfg.enabled()) {
                engineBuilder.option("dap", "%s:%s".formatted(debugCfg.host(), debugCfg.port()));
                engineBuilder.option("dap.Suspend", debugCfg.suspend() ? "true" : "false");
                engineBuilder.option("dap.WaitAttached", debugCfg.waitAttached() ? "true" : "false");
            }
        }
        this.graalEngine = engineBuilder.build();
    }

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
        builder.targetTypeMapping(
                Value.class,
                URI.class,
                v -> v.isString() && URI_PATTERN.matcher(v.asString()).matches(),
                v -> {
                    try {
                        return new URI(v.asString());
                    } catch (URISyntaxException e) {
                        return null;
                    }
                });
        builder.targetTypeMapping(Value.class, Instant.class, Value::isString, v -> Instant.parse(v.asString()));
        builder.targetTypeMapping(Value.class, Path.class, Value::isString, v -> Path.of(v.asString()));
        // For `TypedArray` objects in JS we need to access the `ArrayBuffer` inside the `TypedArray` to
        // get the actual bytes
        builder.targetTypeMapping(Value.class, byte[].class, v -> v.hasMember("buffer"), v -> v.getMember("buffer")
                .as(byte[].class));
        builder.targetTypeMapping(
                Value.class,
                ByteBuffer.class,
                v -> v.hasMember("buffer"),
                v -> ByteBuffer.wrap(v.getMember("buffer").as(byte[].class)));
        // Python `bytes` objects are mapped directly to byte[] by GraalVM, so we can map those directly to ByteBuffer
        // here
        builder.targetTypeMapping(
                Value.class,
                ByteBuffer.class,
                v -> v.getMetaObject().getMetaSimpleName().equals("bytes"),
                v -> ByteBuffer.wrap(v.as(byte[].class)));
        builder.targetTypeMapping(
                Value.class,
                IIIFQuality.class,
                Value::isString,
                v -> IIIFQuality.fromString(v.asString().toLowerCase()));
        builder.targetTypeMapping(
                Value.class,
                IIIFVersion.class,
                Value::isString,
                v -> IIIFVersion.fromString(v.asString().toLowerCase()));
        return builder.build();
    }

    /// Construct a new GraalJS JavaScript context for executing extension code.
    ///
    /// @param wolpiCtx optional Wolpi context to make available to the extension as a global variable
    ///                 `wolpi`, or `null` if it is not available for this context
    /// @return the new GraalVM polyglot context, initialized for use by Wolpi extensions
    public Context getJsContext(@Nullable ExtensionGuestContext wolpiCtx) {
        var ctx = Context.newBuilder("js")
                .allowHostAccess(hostAccess)
                .allowHostClassLookup(c -> true)
                .engine(graalEngine)
                .allowIO(IOAccess.newBuilder().fileSystem(new ESMFileSystem()).build())
                .useSystemExit(false) // Disallow `exit()` calls from JS extensions
                .allowExperimentalOptions(true)
                .option("js.esm-eval-returns-exports", "true")
                .option("js.top-level-await", "true")
                .option("js.text-encoding", "true") // allow TextEncoder/TextDecoder
                .option("js.worker", "true") // allow threading via Worker API
                .build();
        if (wolpiCtx != null) {
            ctx.getBindings("js").putMember("wolpi", PolyglotHelpers.toGuest(wolpiCtx, Language.JAVASCRIPT));
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
    public Context getPythonContext(@Nullable Path venvPath, @Nullable ExtensionGuestContext wolpiCtx) {
        var builder = Context.newBuilder("python")
                .engine(graalEngine)
                .allowHostAccess(hostAccess)
                .allowHostClassLookup(c -> true)
                .allowIO(IOAccess.ALL)
                .useSystemExit(false) // Disallow `exit()` calls from Python extensions
                .allowCreateThread(true)
                .allowCreateProcess(true)
                .allowExperimentalOptions(true)
                .allowNativeAccess(true)
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

        // In Python, the global namespace is reserved for builtins, so we provide access to the
        // guest context by injecting a module named `wolpi` instead that can be imported by the
        // extension.
        ctx.enter();
        try {
            Object guestCtx;
            if (wolpiCtx != null) {
                guestCtx = PolyglotHelpers.toGuest(wolpiCtx, Language.PYTHON);
            } else {
                // If no context is available, the `wolpi` module is just an object with no members
                guestCtx = ProxyObject.fromMap(Map.of());
            }
            ctx.getBindings("python").putMember("__wolpi_module__", guestCtx);
            ctx.eval("python", """
                    import sys
                    sys.modules['wolpi'] = __wolpi_module__
                    """);
        } finally {
            ctx.leave();
        }

        return ctx;
    }

    /// **Internal API only** - Reset the GraalVM engine, closing all existing contexts.
    ///
    /// There should never be a need to call this method outside test classes that have a per-class
    /// lifecycle. The caches in the Engine are absolutely vital for performance and should not be
    /// discarded during normal operation.
    public void resetEngine() {
        graalEngine.close();
        graalEngine = Engine.newBuilder("python", "js").build();
    }
}
