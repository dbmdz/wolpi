package dev.mdz.wolpi.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.logging.LogLevel;

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
        @NestedConfigurationProperty @Nullable HttpConfig http,
        @NestedConfigurationProperty @Nullable LoggingConfig logging,
        @NestedConfigurationProperty IIIFConfig iiif,
        @NestedConfigurationProperty CacheControlHeaders cacheControlHeaders,
        @NestedConfigurationProperty List<ExtensionConfig> extensions,
        @NestedConfigurationProperty ExtensionRuntimeConfig extensionRuntime,
        @NestedConfigurationProperty ExtensionPoolConfig extensionPool,
        @NestedConfigurationProperty ExtensionDebugConfig extensionDebug,
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

    /// Configuration for the extension runtimes.
    ///
    /// @param enablePythonNativeModules Whether to enable support for native Python modules in
    ///                                  extensions (e.g. numpy). Enabled by default, disable if
    ///                                  you want to restrict extensions to pure Python code only.
    public record ExtensionRuntimeConfig(
            @DefaultValue("true") boolean enablePythonNativeModules) {}

    /// Configures limits for the pool handing out extension contexts to requests.
    ///
    /// Use this to tweak memory usage and latency for your workload. More idle extensions means more
    /// memory usage, but also lower latency for requests as new extensions do not need to be
    /// initialized. More total extensions means more concurrent requests can be handled, but also
    /// more memory usage.
    ///
    /// @param minIdle Minimum number of idle extension contexts to keep in the pool per configured
    ///                extension. Contexts will be kept in the pool even if idle to avoid expensive
    ///                recompilation of extension code. Defaults to the number of logical CPU cores
    ///                if not specified. These extensions will be kept in the pool until the server
    ///                is shut down, i.e. they will not be evicted even after the eviction timeout.
    /// @param maxIdle Maximum number of idle extension contexts to keep in the pool per configured
    ///                extension. Set this to the expected average number of concurrent requests.
    ///                These extensions will be kept in the pool until the eviction timeout is reached.
    ///                Defaults to twice the minIdle value.
    /// @param maxTotal Maximum number of total extension contexts to keep in the pool per configured
    ///                 extension (i.e. idle + in-use). When the pool has more total contexts than
    ///                 this for a given extension, requests for new contexts for that extension will
    ///                 block until a context is returned to the pool. Set this to the expected
    ///                 maximum number of concurrent requests. Defaults to twice the maxIdle value.
    /// @param evictionTimeout Duration after which idle contexts above minIdle will be evicted from
    ///                        the pool. Defaults to 30 minutes. This helps free up memory when load
    ///                        decreases, while keeping at least minIdle contexts warm to handle
    ///                        subsequent requests without recompilation overhead.
    public record ExtensionPoolConfig(
            @Nullable Integer minIdle,
            @Nullable Integer maxIdle,
            @Nullable Integer maxTotal,
            @DefaultValue("30m") Duration evictionTimeout) {}

    ///  Configures debugging  for Wolpi extensions.
    ///
    /// If enabled, Wolpi will start a debug server that allows attaching via the Debug Adapter
    /// Protocol (e.g. from VSCode or other IDEs).
    ///
    /// @param enabled Whether to enable the debug server.
    /// @param host Host to listen on for debug connections, defaults to localhost.
    /// @param port Port to listen on for debug connections, defaults to 4711.
    /// @param suspend If true, the extension will suspend execution at the very first source line,
    ///                disabled by default.
    /// @param waitAttached If true, the extension will wait for a debugger to attach before
    ///                     starting extension code execution, disabled by default.
    ///
    public record ExtensionDebugConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("localhost") String host,
            @DefaultValue("4711") int port,
            @DefaultValue("false") boolean suspend,
            @DefaultValue("false") boolean waitAttached) {}

    /// Configuration for the embedded HTTP server.
    ///
    /// @param host Host to bind the server to, defaults to all interfaces.
    /// @param port Port to bind the server to, defaults to 8080.
    /// @param minThreads Minimum number of threads in the server thread pool, defaults to 10
    /// @param maxThreads Maximum number of threads in the server thread pool, defaults to 200
    /// @param maxRequestsAccepted Maximum number of requests the server will accept and queue, if
    ///                            the queue is full, requests will be rejected with a 503 error.
    ///                            Defaults to 100.
    /// @param baseUri Base URI the server is accessible at, used for generating absolute URLs in
    ///                responses, such as in the profile link header. If not set, the server will
    ///                will attempt to determine the base URI from the request headers (`Host` and
    ///                `X-Forwarded-*`)`
    public record HttpConfig(
            @DefaultValue("") String host,
            @DefaultValue("-1") Integer port,
            @DefaultValue("-1") Integer minThreads,
            @DefaultValue("-1") Integer maxThreads,
            @DefaultValue("-1") Integer maxRequestsAccepted,
            @DefaultValue("") String baseUri) {}

    /// Configuration for logging.
    ///
    /// @param format Format of the log output, either `text` or `json`. Defaults to `text`.
    /// @param level Minimum log level, one of `trace`, `debug`, `info`, `warn`, `error`, or
    ///              `off`. Defaults to `info`.
    /// @param logRequestDetailsOnCrash Whether to log the remote address and headers of the request
    ///     that caused a crash, disable this to avoid logging potentially sensitive information.
    ///     Defaults to `true`.
    public record LoggingConfig(
            @DefaultValue("text") LogFormat format,
            @DefaultValue("info") LogLevel level,
            @DefaultValue("true") boolean logRequestDetailsOnCrash) {}

    /// Supported log formats.
    public enum LogFormat {
        /// Human-readable text format, `{timestamp} {level} {logger} {message}`
        TEXT,
        /// Structured JSON format, one JSON object per log line with fields `@timestamp`, `level`,
        /// `logger_name`, `message`, `stack_trace`, `stack_hash`, `request_id`
        JSON
    }
}
