package dev.mdz.wolpi.extension;

import dev.mdz.wolpi.extension.PyPiInstaller.EntryPoint;
import dev.mdz.wolpi.extension.exceptions.ExtensionLoadException;
import dev.mdz.wolpi.extension.model.ExtensionGuestContext;
import dev.mdz.wolpi.extension.model.Language;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// A runtime context for Python extensions.
public class PythonRuntimeContext extends RuntimeContext {
    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final List<String> MODULE_LOCATIONS =
            List.of("/python", "/classes/python", "/BOOT-INF/classes/python");
    private static final Pattern NESTED_JAR_PATTERN = Pattern.compile("^jar:nested:(?<outerJar>.+\\.jar)/!.+$");

    private final Source source;
    private final @Nullable EntryPoint entryPoint;
    private final @Nullable Path venvPath;
    private final @Nullable ExtensionGuestContext guestContext;

    public PythonRuntimeContext(
            Source source,
            @Nullable EntryPoint entryPoint,
            @Nullable Path venvPath,
            @Nullable ExtensionGuestContext extensionGuestContext,
            GraalContextSupplier contextSupplier)
            throws ExtensionLoadException {
        this.source = source;
        this.entryPoint = entryPoint;
        this.venvPath = venvPath;
        this.guestContext = extensionGuestContext;
        super(contextSupplier);
    }

    @Override
    protected Context getGraalContext(GraalContextSupplier contextSupplier) {
        return contextSupplier.getPythonContext(venvPath, guestContext);
    }

    /// Evaluates the extension source and returns the object containing its hooks.
    ///
    /// The hooks object can either be returned by an entry point function (for packaged extensions)
    /// or simply be the top-level scope where all hook functions are defined (for single-file
    /// extensions).
    @Override
    protected Value getExtensionObject() throws ExtensionLoadException {
        this.langContext.enter();
        try {
            langContext.eval(source);
            var bindings = langContext.getBindings("python");

            Value hooks;
            if (entryPoint != null) {
                if (!bindings.hasMember(entryPoint.function())) {
                    throw new IllegalArgumentException(
                            "Entry point function '%s' not found in extension.".formatted(entryPoint.function()));
                }
                hooks = bindings.getMember(entryPoint.function()).execute();
            } else {
                hooks = bindings;
            }

            var functions = hooks.getMemberKeys().stream()
                    .filter(key -> !key.startsWith("_") && hooks.getMember(key).canExecute())
                    .toList();

            if (functions.isEmpty()) {
                throw new ExtensionLoadException("Extension did not define any top-level functions.");
            }
            return hooks;
        } finally {
            this.langContext.leave();
        }
    }

    @Override
    public Language getLang() {
        return Language.PYTHON;
    }

    /// Install a bundled Python module file into the GraalPy context so it can be imported
    /// as a regular module by the extension
    public static void installPythonModuleFromFile(Context context, String moduleName, String fileName) {
        String jarLocation = PythonRuntimeContext.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toString();
        Path absolutePath = Path.of(jarLocation.replace("file:", "")).toAbsolutePath();
        Path filePath;
        boolean deleteAfter;
        try {
            Matcher m = NESTED_JAR_PATTERN.matcher(jarLocation);
            if (m.matches()) {
                absolutePath = Path.of(m.group("outerJar")).toAbsolutePath();
            }
            var finalPath = absolutePath;
            if (finalPath.toString().endsWith(".jar")) {
                try (var jarFs = FileSystems.newFileSystem(finalPath)) {
                    // GraalPy can't import code from a JAR directly, so we write the shim to a temp
                    // file with restricted permissions first
                    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                        filePath = Files.createTempFile(
                                "tmp_wolpi_%s".formatted(moduleName),
                                ".py",
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    } else {
                        filePath = Files.createTempFile("tmp_wolpi_pyvips_shim", ".py");
                        var file = filePath.toFile();
                        file.setReadable(true, true);
                        file.setWritable(true, true);
                    }
                    Path locationInJar = MODULE_LOCATIONS.stream()
                            .map(p -> jarFs.getPath(p).resolve(fileName))
                            .filter(Files::exists)
                            .findFirst()
                            .orElseThrow(() -> new IOException("Failed to locate %s in JAR at expected locations in %s"
                                    .formatted(fileName, finalPath)));
                    Files.write(filePath, Files.readAllBytes(locationInJar));
                    deleteAfter = true;
                }
            } else {
                filePath = MODULE_LOCATIONS.stream()
                        .map(p -> finalPath.resolveSibling(p.substring(1)).resolve(fileName))
                        .filter(Files::exists)
                        .findFirst()
                        .orElseThrow(() -> new IOException(
                                "Failed to locate %s at expected locations in %s".formatted(fileName, finalPath)));
                deleteAfter = false;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var localModuleName = filePath.getFileName().toString().replace(".py", "");
        var moduleDirectory = filePath.getParent().toAbsolutePath().toString();
        var code = String.join(
                "\n",
                "import importlib",
                "import sys",
                "",
                "sys.path.insert(0, '%s')".formatted(moduleDirectory),
                "",
                "_loaded_module = importlib.import_module('%s')".formatted(localModuleName),
                "",
                "if '__wolpi_module__' in globals():",
                "    _loaded_module.__wolpi_module__ = __wolpi_module__",
                "",
                "sys.modules.setdefault('%s', _loaded_module)".formatted(moduleName));
        try {
            context.eval("python", code);
        } finally {
            if (deleteAfter) {
                // Shim can be safely deleted after importing, as it was bytecode-compiled and loaded into
                // memory by the interpreter.
                try {
                    Files.delete(filePath);
                } catch (IOException e) {
                    log.warn("Failed to delete temporary python module file: {}", filePath, e);
                }
            }
        }
    }
}
