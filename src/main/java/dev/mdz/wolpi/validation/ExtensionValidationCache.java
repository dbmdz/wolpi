package dev.mdz.wolpi.validation;

import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.model.LoadedExtension;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/// Manages a cache of validated extension hashes to skip expensive validation on startup
/// when extensions haven't changed.
@Component
public class ExtensionValidationCache {
    private static final Logger log =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final String CACHE_FILE_NAME = "validated-extensions.json";

    private final Path cacheFile;
    private final JsonMapper objectMapper;
    private final Map<String, String> cachedHashes;

    public ExtensionValidationCache(WolpiConfig config, JsonMapper objectMapper) {
        this.cacheFile = config.dataDirectory().resolve(CACHE_FILE_NAME);
        this.objectMapper = objectMapper;
        this.cachedHashes = loadCache();
    }

    /// Load the cache from disk, returning an empty map if the file doesn't exist or can't be read
    private Map<String, String> loadCache() {
        if (!Files.exists(cacheFile)) {
            return new HashMap<>();
        }

        var typeRef = new TypeReference<Map<String, String>>() {};
        try {
            return new HashMap<>(objectMapper.readValue(cacheFile.toFile(), typeRef));
        } catch (Exception e) {
            log.warn("Failed to load validation cache from {}, will re-validate all extensions", cacheFile, e);
            return new HashMap<>();
        }
    }

    /// Save the current cache to disk
    private void saveCache() {
        try {
            Files.createDirectories(cacheFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), cachedHashes);
        } catch (IOException e) {
            log.warn("Failed to save validation cache to {}", cacheFile, e);
        }
    }

    /// Check if the given extension has been validated with its current content hash
    ///
    /// @param ext The extension to check
    /// @param currentHash The current content hash of the extension
    /// @return true if the extension was previously validated with this hash
    public boolean isValidated(LoadedExtension ext, String currentHash) {
        String extensionName = ext.extensionInfo().name();
        String cachedHash = cachedHashes.get(extensionName);
        return currentHash.equals(cachedHash);
    }

    /// Mark the given extension as validated with its current content hash
    ///
    /// @param ext The extension that was successfully validated
    /// @param contentHash The content hash of the validated extension
    public void markValidated(LoadedExtension ext, String contentHash) {
        String extensionName = ext.extensionInfo().name();
        cachedHashes.put(extensionName, contentHash);
        saveCache();
        log.debug("Cached validation result for extension '{}' with hash {}", extensionName, contentHash);
    }

    /// Remove a cached validation result for the given extension
    ///
    /// @param extensionName The name of the extension to invalidate
    public void invalidate(String extensionName) {
        if (cachedHashes.remove(extensionName) != null) {
            saveCache();
            log.debug("Invalidated cached validation for extension '{}'", extensionName);
        }
    }

    /// Clear all cached validation results
    public void clear() {
        cachedHashes.clear();
        try {
            Files.deleteIfExists(cacheFile);
            log.debug("Cleared validation cache");
        } catch (IOException e) {
            log.warn("Failed to delete validation cache file {}", cacheFile, e);
        }
    }
}
