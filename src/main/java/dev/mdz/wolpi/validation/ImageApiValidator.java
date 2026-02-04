package dev.mdz.wolpi.validation;

import dev.mdz.wolpi.extension.ExtensionRegistry;
import dev.mdz.wolpi.extension.GraalContextSupplier;
import dev.mdz.wolpi.extension.PyPiInstaller;
import dev.mdz.wolpi.extension.exceptions.PackageInstallException;
import dev.mdz.wolpi.extension.model.LoadedExtension;
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
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/// Validates the application against the official IIIF Image API test suite
@Component
public class ImageApiValidator {
    private static final String VALIDATOR_VERSION = "0.1.0";
    private static final String VALIDATION_IMAGE_PNG = "67352ccc-d1b0-11e1-89ae-279075081939-png";
    private static final List<String> PYVIPS_SHIM_LOCATIONS = List.of(
            "/python/pyvips_shim.py", "/classes/python/pyvips_shim.py", "/BOOT-INF/classes/python/pyvips_shim.py");

    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final ExtensionRegistry extensionRegistry;
    private final PyPiInstaller pyPiInstaller;
    private final GraalContextSupplier contextSupplier;
    private final JsonMapper objectMapper;

    // These are lazy-initialized when first needed
    private @Nullable List<ValidationTest> allTests = null;
    private @Nullable Path venvPath = null;

    public ImageApiValidator(
            PyPiInstaller pyPiInstaller,
            ExtensionRegistry extensionRegistry,
            GraalContextSupplier contextSupplier,
            JsonMapper mapper) {
        this.pyPiInstaller = pyPiInstaller;
        this.extensionRegistry = extensionRegistry;
        this.contextSupplier = contextSupplier;
        this.objectMapper = mapper;
    }

    /// Install the validator package into a new virtual environment and discover all tests in it
    void installValidator() {
        if (!VALIDATOR_VERSION.equals(this.pyPiInstaller.getVersion("iiif-validator-ng"))) {
            log.info("Installing iiif-validator-ng package for IIIF Image API validation...");
            try {
                this.venvPath = this.pyPiInstaller.install("iiif-validator-ng", VALIDATOR_VERSION, null, null, true);
            } catch (PackageInstallException e) {
                log.error(
                        "Failed to install iiif-validator-ng package for IIIF Image API validation, cannot proceed.",
                        e);
                throw new RuntimeException(e);
            }
        } else {
            this.venvPath = this.pyPiInstaller.getVenv("iiif-validator-ng");
        }
        this.allTests = this.discoverAllTests();
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
        var ctx = contextSupplier.getPythonContext(venvPath, null);
        installPyVipsShim(ctx);
        return ctx;
    }

    /// Run the full IIIF Image API validation test suite against the given local server port
    /// with each installed extension enabled (one at a time) to ensure that no extension breaks
    /// API compliance.
    ///
    /// @param port the local server port to run the tests against
    /// @return `true` if all tests passed with all extensions, `false` if any test failed
    public boolean runStartupExtensionValidation(int port) {
        boolean allPassed = true;
        for (var ext : extensionRegistry.getExtensions()) {
            allPassed = allPassed && validateExtension(ext, port);
        }
        return allPassed;
    }

    /// Validate the given extension in the context of the current application configuration.
    ///
    /// This will auto-detect the features supported by the current configuration and only run
    /// the tests that are applicable to the current configuration.
    ///
    /// If there were test failures, details will be logged at `ERROR` level.
    ///
    /// @param ext  The extension to validate
    /// @param port The local server port Wolpi is currently listening on
    /// @return `true` if all tests passed with the extension enabled, `false` if any test failed
    public boolean validateExtension(LoadedExtension ext, int port) {
        log.info(
                "Running IIIF Image API validation tests with extension '{}'...",
                ext.extensionInfo().name());
        for (IIIFVersion version : IIIFVersion.values()) {
            Map<ValidationTest, List<ValidationResult>> results;
            try (var ignored = extensionRegistry.temporarilyIsolateExtension(ext)) {
                results = this.runTests(
                        "http://localhost:%d/v%d".formatted(port, version.value()), VALIDATION_IMAGE_PNG, version, 4);
            }
            var allFailures = results.entrySet().stream()
                    .map(e -> Map.entry(
                            e.getKey(),
                            e.getValue().stream()
                                    .filter(ValidationFailure.class::isInstance)
                                    .map(ValidationFailure.class::cast)
                                    .toList()))
                    .filter(e -> !e.getValue().isEmpty())
                    .toList();
            if (allFailures.isEmpty()) {
                continue;
            }
            log.error(
                    "Extension '{}' caused IIF Image API v{} validation failures:",
                    ext.extensionInfo().name(),
                    version.value());
            for (var entry : allFailures) {
                var test = entry.getKey();
                for (var failure : entry.getValue()) {
                    log.error(
                            " - Test '{}' ({}): {}\nexpected: '{}'\ngot: '{}'\nURL: {}",
                            test.name(),
                            test.category(),
                            failure.details(),
                            failure.expected(),
                            failure.received(),
                            failure.url());
                }
            }
            return false;
        }
        return true;
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
                    Path shimJarLocation = PYVIPS_SHIM_LOCATIONS.stream()
                            .map(jarFs::getPath)
                            .filter(Files::exists)
                            .findFirst()
                            .orElseThrow(() ->
                                    new IOException("Failed to locate pyvips_shim.py in JAR at expected locations"));
                    Files.write(shimLocation, Files.readAllBytes(shimJarLocation));
                    deleteShimAfter = true;
                }
            } else {
                shimLocation = PYVIPS_SHIM_LOCATIONS.stream()
                        .map(p -> absolutePath.resolveSibling(p.substring(1)))
                        .filter(Files::exists)
                        .findFirst()
                        .orElseThrow(() -> new IOException("Failed to locate pyvips_shim.py at expected locations"));
                deleteShimAfter = false;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        var shimModuleName = shimLocation.getFileName().toString().replace(".py", "");
        var code = """
                import sys

                sys.path.insert(0, '%s')

                import %s

                sys.modules['pyvips'] = %s
                """.formatted(shimLocation.getParent().toAbsolutePath().toString(), shimModuleName, shimModuleName);
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

    /// Run all tests that are applicable to the current configuration
    ///
    /// This delegates the test selection to the `iiif-validator-ng` pacakge, which will download
    /// the info.json from the given baseUrl and select the tests that should pass based on the
    /// compliance level and set of additional features advertised in the info.json.
    ///
    /// Use this if you want to validate the **current application configuration**.
    ///
    /// @param baseUrl Base URL of the IIIF Image API endpoint to validate (i.e. the base URL of the
    ///                image service, without identifiers)
    /// @param testId  Identifier that resolves to the the official IIIF Image Validation test image
    ///                in the application
    /// @param version IIIF Image API version to validate against
    /// @param maxThreads Maximum number of threads to use for running tests in parallel, or null to
    ///                   use the default (4)
    /// @return Map of all tests that were run to their results
    public Map<ValidationTest, List<ValidationResult>> runTests(
            String baseUrl, String testId, IIIFVersion version, @Nullable Integer maxThreads) {
        if (this.venvPath == null) {
            this.installValidator();
        }
        Map<ValidationTest, List<ValidationResult>> results = new HashMap<>();
        try (var context = this.getValidationContext()) {
            var fromPy = context.eval("python", """
          from iiif_validator.tests.test import IIIFVersion, TargetServer
          from iiif_validator.validate import run_tests

          all_results = dict()
          for test, results in run_tests(TargetServer('%s', '%s', IIIFVersion.V%d), max_threads=%s):
              all_results[test] = results
          all_results
          """.formatted(
                            baseUrl, testId, version.value(), maxThreads != null ? maxThreads.toString() : "None"));
            var it = fromPy.getHashEntriesIterator();
            while (it.hasIteratorNextElement()) {
                var entry = it.getIteratorNextElement();
                var entryIt = entry.getIterator();
                var test = entryIt.getIteratorNextElement();
                var testResults = entryIt.getIteratorNextElement();
                var validationTest = ValidationTest.fromPyClass(test);
                List<ValidationResult> validationResults = new ArrayList<>();
                if (testResults.getMetaObject().getMetaSimpleName().equals("list")) {
                    for (int i = 0; i < testResults.getArraySize(); i++) {
                        var res = testResults.getArrayElement(i);
                        if (res.getMember("success").asBoolean()) {
                            validationResults.add(res.as(ValidationSuccess.class));
                        } else {
                            validationResults.add(res.as(ValidationFailure.class));
                        }
                    }
                } else {
                    if (testResults.getMember("success").asBoolean()) {
                        validationResults.add(testResults.as(ValidationSuccess.class));
                    } else {
                        validationResults.add(testResults.as(ValidationFailure.class));
                    }
                }
                results.put(validationTest, validationResults);
            }
        }
        return results;
    }

    /// Run a single test by its identifier with a separate [Context]
    ///
    /// Use this if you want to validate a specific test, regardless of the current
    /// application configuration.
    ///
    /// @param test    The test to run
    /// @param baseUrl Base URL of the IIIF Image API endpoint to validate (i.e. the base URL of the
    ///                image service, without identifiers)
    /// @param testId  Identifier that resolves to the the official IIIF Image Validation test image
    ///                in the application
    /// @param version IIIF Image API version to validate against
    /// @return List of results for the test, usually a single result, but can be multiple if the
    ///         test contains multiple assertions
    public List<ValidationResult> runTest(ValidationTest test, String baseUrl, String testId, IIIFVersion version) {
        try (var context = getValidationContext()) {
            return runTest(test, baseUrl, testId, version, context);
        }
    }

    /// Run a single test by its identifier in the given [Context]
    ///
    /// Use this if you want to run multiple tests in the same context, e.g.
    /// to save the overhead of creating a new context for each test.
    ///
    /// @param test    The test to run
    /// @param baseUrl Base URL of the IIIF Image API endpoint to validate (i.e. the base URL of the
    ///                image service, without identifiers)
    /// @param testId  Identifier that resolves to the the official IIIF Image Validation test image
    ///                in the application
    /// @param version IIIF Image API version to validate against
    /// @param context The Python context to run the test in
    /// @return List of results for the test, usually a single result, but can be multiple if the
    ///         test contains multiple assertions
    public List<ValidationResult> runTest(
            ValidationTest test, String baseUrl, String testId, IIIFVersion version, Context context) {
        List<ValidationResult> results = new ArrayList<>();
        var result = context.eval("python", """
        from iiif_validator.tests.test import TargetServer, IIIFVersion
        %s as TestClass

        TestClass.run(TargetServer('%s', '%s', IIIFVersion.V%d))
        """.formatted(test.pythonImportString(), baseUrl, testId, version.value()));
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

    /// Discover all available tests from the installed validator package.
    ///
    /// This is called during installation of the validator package to populate
    /// the list of all tests.
    ///
    /// @return List of all available tests
    private List<ValidationTest> discoverAllTests() {
        Path venv = this.venvPath;
        assert venv != null;
        Path cachedTests = this.venvPath.resolve("cached_tests-%s.json".formatted(VALIDATOR_VERSION));
        if (Files.exists(cachedTests)) {
            var typeRef = new TypeReference<List<ValidationTest>>() {};
            try {
                return objectMapper.readValue(cachedTests.toFile(), typeRef);
            } catch (JacksonException e) {
                log.warn(
                        "Failed to read cached test list from {}, will re-discover tests",
                        cachedTests.toAbsolutePath(),
                        e);
            }
        }
        log.info("Discovering available IIIF Image API validation tests...");
        List<ValidationTest> tests;
        try (var context = this.getValidationContext()) {
            var pyTestSet = context.eval("python", """
          from iiif_validator.tests.test import get_tests, IIIFVersion

          all_tests = set()
          for test in get_tests(IIIFVersion.V2):
              all_tests.add(test)
          for test in get_tests(IIIFVersion.V3):
              all_tests.add(test)
          list(all_tests)
          """);
            tests = new ArrayList<>();
            for (int i = 0; i < pyTestSet.getArraySize(); i++) {
                var pyClass = pyTestSet.getArrayElement(i);
                tests.add(ValidationTest.fromPyClass(pyClass));
            }
        }
        try {
            objectMapper.writeValue(cachedTests.toFile(), tests);
        } catch (JacksonException e) {
            log.warn(
                    "Failed to write cached test list to {}, will re-discover tests next time",
                    cachedTests.toAbsolutePath(),
                    e);
        }
        return tests;
    }
}
