package dev.mdz.wolpi.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;

public record ExtensionConfig(
        @Nullable Path path,
        @Nullable PkgSource npm,
        @Nullable PkgSource pypi,
        @Nullable Map<String, Object> config,
        @DefaultValue("false") boolean liveReload) {

    public ExtensionConfig {
        // This is a nasty workaround for the fact that Spring flattens the YAML into Properties,
        // which causes lists to be represented as Maps with numeric string keys. We need to clean
        // that up for our extensions so they have the expected data structures.
        if (config != null) {
            config = (Map<String, Object>) fixConfig(config);
        }

        if (pypi != null && pypi.indexAuth != null && pypi.indexAuth.token != null) {
            throw new IllegalArgumentException("PyPI index authentication only supports username/password.");
        }

        if (npm != null && npm.indexAuth != null && !npm.pkg.startsWith("@")) {
            throw new IllegalArgumentException(
                    "npm index authentication is only supported for scoped packages (e.g., @org/package).");
        }
    }

    private static @Nullable Object fixConfig(@Nullable Object input) {
        if (input instanceof Map<?, ?> map) {
            if (isListInDisguise(map)) {
                // The map is known to have numerical oder string keys starting from "0"
                List<Object> list = new ArrayList<>(map.size());
                for (int i = 0; i < map.size(); i++) {
                    Object val = map.get(String.valueOf(i));
                    if (val == null) {
                        val = map.get(i);
                    }
                    var fixedVal = fixConfig(val);
                    if (fixedVal != null) {
                        list.add(fixedVal);
                    }
                }
                return list;
            }

            Map<Object, @Nullable Object> fixedMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                fixedMap.put(entry.getKey(), fixConfig(entry.getValue()));
            }
            return fixedMap;
        }

        return input;
    }

    private static boolean isListInDisguise(Map<?, ?> map) {
        if (map.isEmpty()) {
            // For empty maps/lists, we cannot determine the intent, so we assume it's not a list.
            return false;
        }
        // Spring binds lists to maps with keys "0", "1", "2"...
        // We check if the map contains strictly generic sequential keys.
        for (int i = 0; i < map.size(); i++) {
            // Check for String key "0" or Integer key 0
            if (!map.containsKey(String.valueOf(i)) && !map.containsKey(i)) {
                return false;
            }
        }
        return true;
    }

    public record PkgSource(
            String pkg,
            String version,
            @Nullable URI index,
            @Nullable IndexAuth indexAuth) {}

    public record IndexAuth(
            @Nullable String username,
            @Nullable String password,
            @Nullable String token) {
        public IndexAuth {
            if ((username != null && password == null) || (username == null && password != null)) {
                throw new IllegalArgumentException(
                        "Both username and password must be provided for index authentication.");
            }
            if (token != null && username != null) {
                throw new IllegalArgumentException(
                        "Cannot provide both token and username/password for index authentication.");
            }
        }
    }
}
