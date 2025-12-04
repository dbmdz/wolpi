package dev.mdz.wolpi.validation;

import dev.mdz.wolpi.config.ExtensionConfig;
import dev.mdz.wolpi.extension.model.JSLoadedExtension;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import dev.mdz.wolpi.extension.model.PythonLoadedExtension;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// Calculates content hashes for extensions to detect changes that require re-validation
@Component
public class ExtensionHashCalculator {
    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// Calculate a content hash for the given extension
    ///
    /// For package-based extensions (npm/pypi), the hash is simply the package version string.
    /// For local extensions, the hash is a SHA-1 of all source files in the extension directory
    /// or the single file if it's a single-file extension.
    ///
    /// @param ext The extension to calculate the hash for
    /// @return A content hash string that changes when the extension source changes
    public String calculateHash(LoadedExtension ext) {
        ExtensionConfig config = ext.config();

        if (config.npm() != null) {
            return "npm:" + config.npm().pkg() + "@" + config.npm().version();
        }
        if (config.pypi() != null) {
            return "pypi:" + config.pypi().pkg() + "@" + config.pypi().version();
        }

        if (config.path() != null) {
            try {
                return "local:" + calculateFileHash(config.path(), ext);
            } catch (IOException | NoSuchAlgorithmException e) {
                log.warn(
                        "Failed to calculate hash for local extension at {}, will force re-validation",
                        config.path(),
                        e);
                // Return a unique value that won't match any cached hash, forcing re-validation
                return "invalid:" + System.currentTimeMillis();
            }
        }

        throw new IllegalStateException(
                "Extension has no source information: " + ext.extensionInfo().name());
    }

    /// Calculate SHA-1 hash of all source files for a local extension
    private String calculateFileHash(Path extensionPath, LoadedExtension ext)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");

        if (Files.isRegularFile(extensionPath)) {
            // Single file extension
            updateDigestWithFile(digest, extensionPath);
        } else if (Files.isDirectory(extensionPath)) {
            // Package extension - hash all relevant source files
            try (Stream<Path> files = Files.walk(extensionPath)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> isSourceFile(p, ext))
                        .sorted() // Ensure consistent order
                        .forEach(file -> {
                            try {
                                updateDigestWithFile(digest, file);
                            } catch (IOException e) {
                                log.warn("Failed to read file {} for hashing", file, e);
                            }
                        });
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    /// Update the digest with the contents of a single file
    private void updateDigestWithFile(MessageDigest digest, Path file) throws IOException {
        // Include the relative file path in the hash to detect renames
        digest.update(file.getFileName().toString().getBytes());
        // Include the file contents
        digest.update(Files.readAllBytes(file));
    }

    /// Check if a file is a source file that should be included in the hash
    private boolean isSourceFile(Path path, LoadedExtension ext) {
        String fileName = path.getFileName().toString();

        // Always exclude common non-source files
        if (fileName.equals("package-lock.json")
                || fileName.equals("yarn.lock")
                || fileName.equals("pnpm-lock.yaml")
                || fileName.startsWith(".")) {
            return false;
        }

        // Include source files based on extension type
        return switch (ext) {
            case JSLoadedExtension js ->
                fileName.endsWith(".js") || fileName.endsWith(".mjs") || fileName.endsWith(".cjs");
            case PythonLoadedExtension py -> fileName.endsWith(".py");
        };
    }
}
