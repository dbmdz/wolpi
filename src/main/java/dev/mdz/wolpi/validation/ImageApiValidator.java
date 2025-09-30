package dev.mdz.wolpi.validation;

import dev.mdz.wolpi.extension.ExtensionRegistry;
import dev.mdz.wolpi.extension.GraalContextSupplier;
import dev.mdz.wolpi.extension.PyPiInstaller;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.validation.model.ValidationResult;
import dev.mdz.wolpi.validation.model.ValidationResult.ValidationFailure;
import dev.mdz.wolpi.validation.model.ValidationResult.ValidationSuccess;
import dev.mdz.wolpi.validation.model.ValidationTest;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// Validates the current configuration against the official IIIF Image API test suite
@Component
public class ImageApiValidator {
    private static final String VALIDATOR_VERSION = "0.1.0";

    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final ExtensionRegistry extensionRegistry;
    private final PyPiInstaller pyPiInstaller;

    // These are lazy-initialized when first needed
    private @Nullable List<ValidationTest> allTests = null;
    private @Nullable Path venvPath = null;

    public ImageApiValidator(PyPiInstaller pyPiInstaller, ExtensionRegistry extensionRegistry)
            throws PackageInstallException {
        this.pyPiInstaller = pyPiInstaller;
        this.extensionRegistry = extensionRegistry;
    }

    /// Install the validator package into a new virtual environment and discover all tests in it
    private void installValidator() {
        log.info("Installing iiif-validator-ng package for IIIF Image API validation...");
        try {
            this.venvPath = this.pyPiInstaller.install("iiif-validator-ng", VALIDATOR_VERSION, null, true);
        } catch (PackageInstallException e) {
            log.error("Failed to install iiif-validator-ng package for IIIF Image API validation, cannot proceed.", e);
            throw new RuntimeException(e);
        }
        this.allTests = this.discoverAllTests(this.venvPath);
    }

    /// Get all available validation tests from the installed validator package
    public List<ValidationTest> getAllTests() {
        if (this.allTests == null) {
            this.installValidator();
        }
        return allTests;
    }

    ///  Get a GraalVM polyglot context with the validator package installed and ready to use
    public Context getValidationContext() {
        if (this.venvPath == null) {
            this.installValidator();
        }
        var ctx = GraalContextSupplier.getPythonContext(venvPath, null);
        installPyVipsShim(ctx);
        return ctx;
    }

    /// Install a shim for the `pyvips` library into the given Python context.
    ///
    /// iiif-validator-ng uses pyvips to validate image transformations, and we use vips-ffm
    /// anyway internally, so we skip the expensive pyvips installation and just provide
    /// a shim that pretends to be pyvips and calls out to vips-ffm instead.
    private static void installPyVipsShim(Context context) {
        String jarLocation = ImageApiValidator.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toString();
        Path absolutePath = Path.of(jarLocation.replace("file:", "")).toAbsolutePath();
        Path shimLocation;
        boolean deleteShimAfter;
        try {
            if (jarLocation.endsWith(".jar")) {
                try (var jarFs = FileSystems.newFileSystem(absolutePath)) {
                    // GraalPy can't import code from a JAR directly, so we write the shim to a temp
                    // file with restricted permissions first
                    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                        shimLocation = Files.createTempFile(
                                "tmp_wolpi_pyvips_shim",
                                ".py",
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    } else {
                        shimLocation = Files.createTempFile("tmp_wolpi_pyvips_shim", ".py");
                        var file = shimLocation.toFile();
                        file.setReadable(true, true);
                        file.setWritable(true, true);
                    }
                    Files.write(shimLocation, Files.readAllBytes(jarFs.getPath("classes", "python", "pyvips_shim.py")));
                    deleteShimAfter = true;
                }
            } else {
                shimLocation = absolutePath.resolveSibling("classes/python/pyvips_shim.py");
                deleteShimAfter = false;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var shimModuleName = shimLocation.getFileName().toString().replace(".py", "");
        var code =
                """
        import sys

        sys.path.insert(0, '%s')

        import %s

        sys.modules['pyvips'] = %s
        """
                        .formatted(
                                shimLocation.getParent().toAbsolutePath().toString(), shimModuleName, shimModuleName);
        try {
            context.eval("python", code);
        } finally {
            if (deleteShimAfter) {
                // Shim can be safely deleted after importing, as it was bytecode-compiled and loaded into
                // memory by the interpreter.
                try {
                    Files.delete(shimLocation);
                } catch (IOException e) {
                    log.warn("Failed to delete temporary pyvips shim file: {}", shimLocation, e);
                }
            }
        }
    }

    public Map<ValidationTest, List<ValidationResult>> runTests(
            String baseUrl, String testId, IIIFVersion version, @Nullable Integer maxThreads) {
        if (this.venvPath == null) {
            this.installValidator();
        }
        Map<ValidationTest, List<ValidationResult>> results = new HashMap<>();
        try (var context = this.getValidationContext()) {
            var fromPy = context.eval(
                    "python",
                    """
          from iiif_validator.tests.test import IIIFVersion, TargetServer
          from iiif_validator.validation import run_tests

          all_results = dict()
          for test, results in run_tests(TargetServer('%s', '%s', IIIFVersion.V%d), max_threads=%s):
              all_results[test] = results
          all_results
          """
                            .formatted(
                                    baseUrl,
                                    testId,
                                    version.value(),
                                    maxThreads != null ? maxThreads.toString() : "None"));
            var it = fromPy.getHashEntriesIterator();
            while (it.hasIteratorNextElement()) {
                var entry = it.getIteratorNextElement();
                var test = entry.getIteratorNextElement();
                var testResults = entry.getIteratorNextElement();
                var validationTest = ValidationTest.fromPyClass(test);
                List<ValidationResult> validationResults = new ArrayList<>();
                for (int i = 0; i < testResults.getArraySize(); i++) {
                    var res = testResults.getArrayElement(i);
                    if (res.getMember("success").asBoolean()) {
                        validationResults.add(res.as(ValidationSuccess.class));
                    } else {
                        validationResults.add(res.as(ValidationFailure.class));
                    }
                }
                results.put(validationTest, validationResults);
            }
        }
        return results;
    }

    public List<ValidationResult> runTest(ValidationTest test, String baseUrl, String testId, IIIFVersion version) {
        try (var context = getValidationContext()) {
            return runTest(test, baseUrl, testId, version, context);
        }
    }

    public List<ValidationResult> runTest(
            ValidationTest test, String baseUrl, String testId, IIIFVersion version, Context context) {
        List<ValidationResult> results = new ArrayList<>();
        var result = context.eval(
                "python",
                """
        from iiif_validator.tests.test import TargetServer, IIIFVersion
        %s as TestClass

        TestClass.run(TargetServer('%s', '%s', IIIFVersion.V%d))
        """
                        .formatted(test.pythonImportString(), baseUrl, testId, version.value()));
        if (result.getMetaObject().getMetaSimpleName().equals("list")) {
            for (int i = 0; i < result.getArraySize(); i++) {
                var res = result.getArrayElement(i);
                results.add(
                        switch (res.getMetaObject().getMetaSimpleName()) {
                            case "ValidationSuccess" -> res.as(ValidationSuccess.class);
                            case "ValidationFailure" -> res.as(ValidationFailure.class);
                            default ->
                                throw new IllegalStateException("Unexpected result type: "
                                        + res.getMetaObject().getMetaSimpleName());
                        });
            }
        } else {
            results.add(
                    switch (result.getMetaObject().getMetaSimpleName()) {
                        case "ValidationSuccess" -> result.as(ValidationSuccess.class);
                        case "ValidationFailure" -> result.as(ValidationFailure.class);
                        default ->
                            throw new IllegalStateException("Unexpected result type: "
                                    + result.getMetaObject().getMetaSimpleName());
                    });
        }
        return results;
    }

    private List<ValidationTest> discoverAllTests(Path venvPath) {
        try (var context = this.getValidationContext()) {
            var pyTestSet = context.eval(
                    "python",
                    """
          from iiif_validator.tests.test import get_tests, IIIFVersion

          all_tests = set()
          for test in get_tests(IIIFVersion.V2):
              all_tests.add(test)
          for test in get_tests(IIIFVersion.V3):
              all_tests.add(test)
          list(all_tests)
          """);
            List<ValidationTest> tests = new ArrayList<>();
            for (int i = 0; i < pyTestSet.getArraySize(); i++) {
                var pyClass = pyTestSet.getArrayElement(i);
                tests.add(ValidationTest.fromPyClass(pyClass));
            }
            return tests;
        }
    }
}
