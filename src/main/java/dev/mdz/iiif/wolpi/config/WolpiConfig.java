package dev.mdz.iiif.wolpi.config;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

///  Wolpi Configuration, see src/main/resources/application.yml for defaults
/// @param imageBaseDir base directory to resolve images against when no resolving extension is used
/// @param iiif IIIF-specific configuration
/// @param cacheControlHeaders Cache-Control header values for info.json and image responses
/// @param encodingOptions Encoding options for image processing, such as JPEG quality and PNG
// compression level,
///                        will be passed directly to the VIPS *save functions, see their respective
///                        documentation at in the [VIPS API
// docs](https://www.libvips.org/API/current/).
///                        Make sure the YAML value types match the expected types in the VIPS API.
@ConfigurationProperties(prefix = "wolpi")
public record WolpiConfig(
    Path imageBaseDir,
    @NestedConfigurationProperty IIIFConfig iiif,
    @NestedConfigurationProperty CacheControlHeaders cacheControlHeaders,
    Map<String, Map<String, Object>> encodingOptions) {
  public record CacheControlHeaders(String infoJson, String images) {}
}
