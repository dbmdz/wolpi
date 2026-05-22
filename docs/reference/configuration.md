# Configuration

This reference documents the configuration keys supported by Wolpi.

## Specifying the Configuration File

By default, Wolpi looks for a configuration file named `wolpi.yml` or `wolpi.yaml` in the current
working directory.

You can specify a different configuration file location by either:

- setting the `WOLPI_CONFIG` environment variable with the path to your YAML file (`WOLPI_CONFIG=/path/to/wolpi.yml`), or
- passing the `--config=<path>` command-line argument when starting Wolpi (`java -jar wolpi.jar --config=/path/to/wolpi.yml`).
  Make sure to include the `=` between the `--config` flag and the path!

## General Configuration

Top-level configuration keys:

- `data-directory`: Location where Wolpi stores extensions installed from packages; defaults to
  `./data`.
- `image-base-dir`: Fallback location for images if no resolving extension provides the asset;
  defaults to `./images`.

## HTTP configuration

Controls how Wolpi binds its embedded HTTP server and how it generates public absolute URLs.

- `host`: Host or IP address to bind the server to. Default: unset, which means Wolpi leaves the
  embedded server's default bind behavior unchanged.
- `port`: TCP port to bind the server to. Default: unset, which means Spring Boot's default port
  of `8080` is used.
- `base-uri`: Base URI the server is accessible at, used for generating absolute URLs in responses,
  such as canonical link headers and generated `id` values in `info.json`. If unset, Wolpi derives
  the public base URL from the request headers. Do not end this value with a slash.
- `min-threads`: Minimum number of request-handling threads. Default: unset, which means Wolpi
  leaves the embedded Tomcat default unchanged.
- `max-threads`: Maximum number of request-handling threads. Default: unset, which means Wolpi
  leaves the embedded Tomcat default unchanged.
- `max-requests-accepted`: Maximum number of queued requests before new requests are rejected with
  `503`. Default: unset, which means Wolpi leaves the embedded Tomcat default unchanged.

Example:

```yaml
http:
  host: 0.0.0.0
  port: 8080
  base-uri: https://images.example.org/iiif
```

## Logging configuration

Controls Wolpi's own application logging. Wolpi always logs to standard output.

- `format`: Log format, either `text` (colored, human-readable) or `json` (structured),
  defaults to `text`
- `level`: Log level, one of `trace`, `debug`, `info`, `warn`, `error`, or `off`; defaults to
  `info`
- `log-request-details-on-crash`: Whether to include the remote client address and request headers
  when logging uncaught exceptions that occur while handling requests, defaults to `true`. Disable
  if you consider these details to be sensitive information in your setup and don't want them to end
  up in logs.

Example:

```yaml
logging:
  level: info
  format: json
  log-request-details-on-crash: false
```

## HTTP caching headers

Wolpi's resolving system will set HTTP caching headers (`ETag`, `Last-Modified`) on responses based
on the information available from the file system or resolving extensions. Use the
`cache-control-headers` section to configure the `Cache-Control` header values Wolpi sends.

For the request-flow background on where this validation happens, see
[Request and Image Processing Pipeline](../concepts/request-and-image-processing-pipeline.md).

- `info-json`: Cache-Control header value for `info.json` responses, defaults to
  `public, max-age=604800, must-revalidate`
- `images`: Cache-Control header value for image responses, defaults to
  `public, max-age=604800, must-revalidate`

Example:

```yaml
cache-control-headers:
  info-json: "public, max-age=86400, must-revalidate"
  images: "public, max-age=604800, must-revalidate"
```

## Image encoding options

Encoding options for image processing, such as JPEG quality and PNG compression level, can be
set under the `encoding-options` section. These options will be passed directly to the VIPS `save`
functions; see their respective documentation in the [VIPS API][vips-api] (e.g., for
[JPEG][vips-jpg], [PNG][vips-png] or [WebP][vips-webp]).

For the conceptual background on how Wolpi plans requests while libvips performs loading,
processing, and encoding, see
[Request and Image Processing Pipeline](../concepts/request-and-image-processing-pipeline.md).

The section is keyed by the lowercase IIIF name for the image format (see
[table 4.5 "Format" in the spec][iiif-formats]). For primitive values, make sure the YAML value
types match the expected types in the VIPS API (e.g. integers for `gint`, booleans for `gboolean`, etc),
for enum values use the exact value name (as a YAML string) as in the VIPS API documentation for the
enum, e.g. `VIPS_FOREIGN_SUBSAMPLE_ON` for a [`VipsForeignSubsample`][vips-subsample] option.

The defaults are as follows:

```yaml
encoding-options:
  jpg:
    Q: 80
    optimize_coding: true
    interlace: false
  tif:
    Q: 80
    compression: "VIPS_FOREIGN_TIFF_COMPRESSION_JPEG"
    tile: true
    tile_width: 512
    tile_height: 512
  webp:
    Q: 80
    # You'll probably want to adjust this if you're serving paintings or photographs
    preset: VIPS_FOREIGN_WEBP_PRESET_TEXT
    effort: 3
```

[vips-api]: https://www.libvips.org/API/current/
[vips-jpg]: https://www.libvips.org/API/8.17/method.Image.jpegsave.html
[vips-png]: https://www.libvips.org/API/8.17/method.Image.pngsave.html
[vips-webp]: https://www.libvips.org/API/8.17/method.Image.webpsave.html
[vips-subsample]: https://www.libvips.org/API/8.17/enum.ForeignSubsample.html
[iiif-formats]: https://iiif.io/api/image/3.0/#45-format

## IIIF configuration

The `iiif` section controls Wolpi's supported IIIF Image API features, output formats, and
request limits. These settings affect both runtime behavior and the capabilities advertised in
`info.json`.

Wolpi supports various **optional features** of the IIIF Image API specification that can be enabled or
disabled through the `iiif.features` section. By default,
all features (except if otherwise stated) are *enabled*.

The main default-off exception is `iiif.features.scaling.allow-upscaling`, which defaults to
`false`.

### Limits

The `iiif.limits` section caps the size of generated images.

- `max-width`: Maximum output width in pixels. Default: `0`, which means no limit.
- `max-height`: Maximum output height in pixels. Default: `0`, which means no limit.
- `max-area`: Maximum output area in pixels (`width * height`). Default: `0`, which means no limit.

### General Features

These keys live directly under `iiif.features`:

- `profile-link-header`: Whether to include the IIIF profile link header in image responses.
- `json-ld-media-type`: Whether to return `info.json` using the JSON-LD media type.
- `cors`: Whether to enable CORS headers on IIIF responses.
- `canonical-link-header`: Whether to include a canonical link header in image responses.
- `canonical-redirect`: Whether to redirect non-canonical image requests to their canonical URL.
- `base-uri-redirect`: Whether to redirect to the info.json endpoint when accessing the base URI
  without image parameters or an `/info.json` suffix.

### Cropping / region features

These settings control the supported syntax for the region component for image requests and
are all set as entries under the `region` subsection in the `iiif.features` section:

- `by-percent`: Whether to allow regions specified by percent with `pct:x,y,w,h`.
- `by-pixels`: Whether to allow regions specified by pixels with `x,y,w,h`.
- `square`: Whether to allow square regions with `square`.

### Scaling Features

These settings control the supported syntax for the scaling component for image requests and
are all set as entries under the `scaling` subsection in the `iiif.features` section:

- `by-confined-width-height`: Whether to allow scaling by confined width and height with `!w,h`.
- `by-height`: Whether to allow scaling by height with `,h`.
- `by-width`: Whether to allow scaling by width with `w,`.
- `by-percent`: Whether to allow scaling by percent with `pct:`.
- `allow-upscaling`: Whether to allow scaling to sizes larger than the source image. Defaults to
  `false`.
- `by-arbitrary-dimensions`: Whether to allow scaling to arbitrary, non-aspect-ratio-preserving
  dimensions with `w,h`.

### Rotation Features

These settings control the supported syntax for the rotation component for image requests and
are all set as entries under the `rotation` subsection in the `iiif.features` section:

- `mirroring`: Whether to allow mirroring of images on the vertical axis with `!`.
- `by90-degree-rotation`: Whether to allow 90-degree rotations with `0,90,180,270`.
- `arbitrary`: Whether to allow arbitrary rotation angles, not just multiples of 90 degrees.

### Supported Qualities

The `iiif.qualities` section controls the supported quality values for image requests.

- `allowed`: List of quality values that are supported, defaults to all qualities from the IIIF
  Image API specification (`color`, `gray`, `bitonal`).
- `default-quality`: Quality value that will be used when the `default` quality is requested. This
  must be one of the values listed in `allowed`. Defaults to `color`.

### Supported Image Output Formats

The `iiif.formats` section controls the supported output formats for image requests.

- `allowed`: List of image formats (as the [extension string from the IIIF Image
  API spec][iiif-spec-exts], e.g. `jpg` or `png`). Defaults to all formats defined in the IIIF Image API
  specification except `pdf` (`jpg`, `png`, `tif`, `webp`, `gif`, `jp2`).
- `preferred`: List of image formats that will be listed as the preferred formats in the `info.json`
  response for IIIF Image API 3. Must be a subset of the allowed formats. Defaults to
  `jpg`, `png`, `webp`.

Example:

```yaml
iiif:
  features:
    canonical-redirect: true
    cors: true
    scaling:
      by-width: true
      by-height: true
      by-percent: true
      by-confined-width-height: true
      by-arbitrary-dimensions: true
      allow-upscaling: false
  qualities:
    allowed: [color, gray, bitonal]
    default-quality: color
  formats:
    allowed: [jpg, png, webp]
    preferred: [jpg, webp]
  limits:
    max-width: 0
    max-height: 0
    max-area: 0
```

[iiif-spec-exts]: https://iiif.io/api/image/3.0/#45-format

## Extension Configuration

Extensions are configured through the top-level `extensions` list. Each list entry supports:

- `path`: Path to a local extension file or local extension package directory.
- `npm`: Description of a JavaScript package from an npm-compatible registry.
- `pypi`: Description of a Python package from a PyPI-compatible repository.
- `config`: Extension-specific configuration data passed through to the extension.
- `live-reload`: Whether local changes should trigger extension reloads automatically. Defaults to
  `false`.

For `npm` and `pypi` sources, the following keys are supported:

- `pkg`: Package name.
- `version`: Package version to install.
- `index`: Custom package index URL. Optional.
- `index-auth`: Authentication settings for the package index. Optional.

The `index-auth` section supports:

- `username`
- `password`
- `token`

Constraints:

- For PyPI sources, `index-auth` supports `username` and `password` only.
- For npm sources, `index-auth` is supported only for scoped packages such as `@org/package`.
- `username` and `password` must be provided together.
- `token` cannot be combined with `username` / `password`.

Example:

```yaml
extensions:
  - path: /opt/wolpi/extensions/local-extension
    live-reload: false
    config:
      exampleFlag: true
  - npm:
      pkg: "@my-org/private-extension"
      version: "1.2.3"
      index: "https://npm.example.org/repository/npm-private/"
      index-auth:
        token: "<npm-token>"
  - pypi:
      pkg: "my-pypi-extension"
      version: "0.4.0"
      index: "https://pypi.example.org/simple"
      index-auth:
        username: "<username>"
        password: "<password>"
```

For end-to-end installation examples, refer to the [extensions how-to][exts-doc].

[exts-doc]: ../how-to/install-extensions.md#configuration

## Extension Runtime Configuration

The `extension-runtime` section controls runtime behavior shared by all extension execution
environments.

- `enable-python-native-modules`: Whether to allow native Python modules in extensions. Defaults to
  `true`.

Disable this only if you explicitly want to restrict Python extensions to pure Python code and do
not want GraalPy native module access.

## Extension Pool Configuration

Wolpi keeps extension runtimes in a pool so it can reuse them across requests.

- `min-idle`: Minimum number of warm idle extension contexts to keep per configured extension.
  Defaults to the number of logical CPU cores.
- `max-idle`: Maximum number of idle extension contexts to keep per configured extension. Defaults
  to `2 * min-idle`.
- `max-total`: Maximum number of total contexts per configured extension, including borrowed and
  idle ones. Defaults to `2 * max-idle`.
- `eviction-timeout`: How long idle contexts above `min-idle` may remain in the pool before they
  are evicted. Defaults to `30m`.

These settings trade off:

- memory use
- startup and steady-state latency
- concurrent request capacity for extension-backed workloads

For sizing guidance, see [Optimizing the configuration for better performance][pool-tuning].

[pool-tuning]: ../how-to/optimize-the-configuration-for-better-performance.md#extension-pool-configuration

## Extension Debug Configuration

The `extension-debug` section enables debugging for extensions through the Debug Adapter Protocol.

- `enabled`: Whether extension debugging is enabled. Defaults to `false`.
- `host`: Host to bind the debug server to. Defaults to `localhost`.
- `port`: Port to bind the debug server to. Defaults to `4711`.
- `suspend`: Whether to suspend execution at the first source line. Defaults to `false`.
- `waitAttached`: Whether extension execution should wait until a debugger connects. Defaults to
  `false`.

Use this only in development environments. It changes extension startup and execution behavior and
should normally remain disabled in production.

## Extension Packaging Configuration

The `packaging` section controls how Wolpi invokes package managers when installing extensions and
validation dependencies.

- `npm-executable`: Path to the `npm` executable. Default: auto-discovered from `PATH`.
- `python-executable`: Path to the Python executable. Default: auto-discovered from `PATH` by
  checking `graalpy`, `python3`, and `python` in that order.
- `install-timeout`: Timeout for package-manager processes. Defaults to `180s`.

If you need Python extensions with native dependencies, set `python-executable` to a compatible
standalone `graalpy` executable.

Example:

```yaml
packaging:
  npm-executable: '/usr/bin/npm'
  python-executable: '/usr/local/bin/graalpy'
  install-timeout: 5m
```

## Full example configuration

```yaml
data-directory: "./data"
image-base-dir: "./images"
http:
  host: "0.0.0.0"
  port: 8080
logging:
  level: info
  format: text
  log-request-details-on-crash: true
extensions: []
extension-runtime:
  enable-python-native-modules: true
extension-pool:
  eviction-timeout: 30m
extension-debug:
  enabled: false
  host: localhost
  port: 4711
  suspend: false
  waitAttached: false
packaging:
  install-timeout: 180s
cache-control-headers:
  images: "public, max-age=604800, must-revalidate"
  info-json: "public, max-age=604800, must-revalidate"
encoding-options:
  jpg:
    Q: 80
    optimize_coding: true
    interlace: false
    # overshoot_deringing: true # only available with mozjpeg
    # quant_table: 3 # mozjpeg default table
  tif:
    Q: 80
    compression: "VIPS_FOREIGN_TIFF_COMPRESSION_JPEG"
    tile: true
    tile_width: 512
    tile_height: 512
  webp:
    Q: 80
    preset: VIPS_FOREIGN_WEBP_PRESET_TEXT
    effort: 3
iiif:
  features:
    scaling:
      by-confined-width-height: true
      by-height: true
      by-width: true
      by-percent: true
      allow-upscaling: false
      by-arbitrary-dimensions: true
    region:
      by-percent: true
      by-pixels: true
      square: true
    rotation:
      mirroring: true
      by90-degree-rotation: true
      arbitrary: true
    profile-link-header: true
    json-ld-media-type: true
    cors: true
    canonical-link-header: true
    canonical-redirect: true
    base-uri-redirect: true
  qualities:
    allowed:
      - color
      - gray
      - bitonal
    default-quality: color
  formats:
    allowed:
      - jpg
      - tif
      - png
      - gif
      - jp2
      - webp
    preferred:
      - jpg
      - webp
      - png
  limits:
    max-width: 0
    max-height: 0
    max-area: 0
```

##  👷🪜🕳️ Escape-Hatch: Spring Boot configuration { #spring-boot-escape-hatch }

Wolpi is built on top of [Spring Boot][spring-boot] and you can use any of its [supported configuration
options][spring-boot-config] to further customize your setup.

To do so, put the desired Spring Boot configuration options under the `spring` key in your
`wolpi.yml` configuration file. For example, to customize the IPs that Wolpi trusts `X-Forwarded-For`
headers from, you could specify this:

```yaml
spring: # (1)!
  server:
    tomcat:
      remoteip:
        internal-proxies: "10.192.37.\d{1,3}"# (2)!
```

1.  This is the top-level key under which all Spring Boot configuration options go.
2.  See [`server.tomcat.remoteip.internal-proxies` in the Spring Boot documentation][internal-proxies]
    for  details on the format of this setting, by default it trusts all private IP ranges.

[internal-proxies]: https://docs.spring.io/spring-boot/appendix/application-properties/index.html#application-properties.server.server.tomcat.remoteip.internal-proxies

**Please note:** You do this at your own risk, these settings can in rare cases break certain Wolpi
functionality if set incorrectly or expose you to additional security risks. Make sure you understand
the implications of changing these settings before doing so.

## Related reference

- [HTTP Integration](./http.md) for redirects, CORS, cache validation, and response semantics
- [CLI](./cli.md) for `validate` and `install-extensions`
- [Observability](./observability.md) for health endpoints, metrics, and logging

[spring-boot]: https://spring.io/projects/spring-boot
[spring-boot-config]: https://docs.spring.io/spring-boot/appendix/application-properties/index.html
