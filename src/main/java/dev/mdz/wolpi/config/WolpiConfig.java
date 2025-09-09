package dev.mdz.wolpi.config;

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
/// @param extensions List of configured extensions
/// @param extensionPool Configuration for the extension context pool
/// @param packaging Configuration for installing extensions from package managers
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
    @NestedConfigurationProperty ExtensionPoolConfig extensionPool,
    @NestedConfigurationProperty PackagingConfig packaging,
    Map<String, Map<String, Object>> encodingOptions) {

  /// Configures the Cache-Control header for the IIIF endpoints
  ///
  /// @param infoJson Cache-Control header value for info.json responses
  /// @param images Cache-Control header value for image responses
  public record CacheControlHeaders(String infoJson, String images) {}

  /// Configure how extensions are installed from package managers.
  ///
  /// @param npmExecutable Path to the `npm` executable to use for installing npm packages, if null,
  ///                      the system PATH will be searched for `npm`.
  /// @param pythonExecutable Path to the `python` executable to use for installing Python packages,
  ///                         if null, the system PATH will be searched for `graalpy`, `python3` and
  ///                         `python`. Set this to the path of the `graalpy` executable if using
  ///                         native dependencies with extensions is required.
  /// @param installTimeout Timeout for the package manager processes, should be long enough to
  ///                       allow for downloading and installing packages.
  public record PackagingConfig(
      @Nullable Path npmExecutable, @Nullable Path pythonExecutable, Duration installTimeout) {}

  /// Configures limits for the pool handing out extension contexts to requests.
  ///
  /// Use this to tweak memory usage and latency for your workload. More idle extensions means more
  /// memory usage, but also lower latency for requests as new extensions do not need to be
  /// initialized. More total extensions means more concurrent requests can be handled, but also
  /// more memory usage.
  ///
  /// @param maxIdle Maximum number of idle extension contexts to keep in the pool per configured
  ///                extension. Set this to the expected average number of concurrent requests.
  /// @param maxTotal Maximum number of total extension contexts to keep in the pool per configured
  ///                 extension (i.e. idle + in-use). When the pool has more total contexts than
  ///                 this for a given extension, requests for new contexts for that extension will
  ///                 block until a context is returned to the pool. Set this to the expected
  ///                 maximum number of concurrent requests.
  public record ExtensionPoolConfig(int maxIdle, int maxTotal) {}
}
