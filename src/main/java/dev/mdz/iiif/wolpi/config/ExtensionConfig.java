package dev.mdz.iiif.wolpi.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record ExtensionConfig(
    @Nullable Path path,
    @Nullable PkgSource npm,
    @Nullable PkgSource pypi,
    @Nullable Map<String, Object> config) {

  public record PkgSource(String pkg, String version, @Nullable URI index) {}
}
