package dev.mdz.iiif.wolpi.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

///  Wolpi Configuration, see src/main/resources/application.yml for defaults
/// @param dataDirectory directory to store installed extensions and other data in
/// @param imageBaseDir base directory to resolve images against when no resolving extension is used
/// @param iiif IIIF-specific configuration
/// @param cacheControlHeaders Cache-Control header values for info.json and image responses
/// @param encodingOptions Encoding options for image processing, such as JPEG quality and PNG
/// compression level, will be passed directly to the VIPS *save functions,
/// see their respective documentation in the [VIPS API](https://www.libvips.org/API/current/).
/// The object is keyed by the IIIF name for the image format (see
/// [table 4.5 "Format" in the spec](https://iiif.io/api/image/3.0/#45-format))
/// For primitive values, make sure the YAML value types match the expected types in the VIPS API,
/// for enum values use the exact value name as in the VIPS API documentation for the enum, e.g.
/// `VIPS_FOREIGN_SUBSAMPLE_ON`.
@ConfigurationProperties(prefix = "wolpi")
public record WolpiConfig(
    Path dataDirectory,
    Path imageBaseDir,
    @NestedConfigurationProperty IIIFConfig iiif,
    @NestedConfigurationProperty CacheControlHeaders cacheControlHeaders,
    @NestedConfigurationProperty List<ExtensionConfig> extensions,
    @NestedConfigurationProperty PackagingConfig packaging,
    Map<String, Map<String, Object>> encodingOptions) {
  public record CacheControlHeaders(String infoJson, String images) {}

  public record PackagingConfig(
      @Nullable Path npmExecutable, @Nullable Path pythonExecutable, Duration installTimeout) {}
}
