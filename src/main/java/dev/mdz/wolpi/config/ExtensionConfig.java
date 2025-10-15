package dev.mdz.wolpi.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.DefaultValue;

public record ExtensionConfig(
        @Nullable Path path,
        @Nullable PkgSource npm,
        @Nullable PkgSource pypi,
        @Nullable Map<String, Object> config,
        @DefaultValue("false") boolean liveReload) {

    public record PkgSource(String pkg, String version, @Nullable URI index) {}
}
