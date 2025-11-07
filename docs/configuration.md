# Configuration

Wolpi can be configured through a YAML-based configuration file. This file allows you to customize
various aspects of Wolpi's behavior. This guide will walk you through the available configuration
sections.

All configuration options are specified under the `wolpi` root key.

## General configuration

There are a few top-level configuration options that control general aspects of Wolpi's behavior:

- `data-directory`: Location where Wolpi will store extensions installed from packages, defaults to
  `./data`
- `image-base-dir`: Fallback location where images are loaded from if no resolving extension provides
   the image, defaults to `./images`

## HTTP configuration

These options can be set under the `http` section to customize Wolpi's HTTP server behavior:

- `host`: Host or IP to bind the server to, defaults to all interfaces.
- `port`: Port to bind the server to, defaults to 8080.
- `base-uri`: Base URI the server is accessible at, used for generating absolute URLs in responses,
  such as in the profile link header. If not set, the server will  will attempt to determine the base
  URI from the request headers (`Host` and `X-Forwarded-*`)`

Wolpi uses a thread-based request handling model (where each request is handled on a separate thread
from a pool) that you can fine-tune with these settings:

- `min-threads` Minimum number of threads in the server thread pool, defaults to 10
- `max-threads` Maximum number of threads in the server thread pool, defaults to 200
- `max-requests-accepted`: Maximum number of requests the server will accept and queue, if the queue
  is full, requests will be rejected with a 503 error. Defaults to `100`.

## Logging configuration

Wolpi will always log to standard output.

You can customize Wolpi's logging behavior through the `logging` section:

- `format`: Log format, either `text` (colored, human-readable) or `json` (structured),
  defaults to `text`
- `level`: Log level, one of `debug`, `info`, `warn`, `error`, defaults to `info`

## HTTP Caching Headers
Wolpi's resolving system will set HTTP caching headers (`ETag`, `Last-Modified`) on responses based
on the information available from the file system or resolving extension. You can customize the
content of the `Cache-Control` header with `cache-control-headers` opton:

- `info-json`: Cache-Control header value for `info.json` responses, defaults to no header being set 
- `image`: Cache-Control header value for image responses, defaults to no header being set.

## Image Encoding options

Encoding options for image processing, such as JPEG quality and PNG  compression level can be
set under the `image-encoding` section. These options  will be  passed directly to the VIPS *save
functions,  see their respective documentation in the [VIPS API][vips-api] (e.g. for
[JPEG][vips-jpg], [PNG][vips-png] or [WebP][vips-webp]).

The section is keyed by the lowercase IIIF name for the image format (see
[table 4.5 "Format" in the spec][iiif-formats]).  For primitive values, make sure the YAML value
types match the expected types in the VIPS API (e.g. integers for `gint`, booleans for `gboolean`, etc),
for enum values use the exact value name (as a YAML string) as in the VIPS API documentation for the
enum, e.g. `VIPS_FOREIGN_SUBSAMPLE_ON` for a [`VipsForeignSubsample`][vips-subsample] option.

The defaults are as follows:

```yaml
image-encoding:
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

## IIIF Settings

Many aspects of Wolpi's IIIF Image API behavior can be configured through the `iiif` section. All
of these settings will impact the compliance level and the set of additional features described in the
`info.json` responses.

- `restrict-to-sizes`: If set to `true`, Wolpi will only allow requests for sizes that are
  explicitly available in the source images (i.e. the tiles encoded into a TIF or JP2 image, or
  the full resolution image). This can improve performance and increase cache hit rates, at the cost
  of a loss of flexibility for clients. Defaults to `false`. Will cause the compliance level to be
  reduced to "level 0" if enabled.

Wolpi supports various **optional features** of the IIIF Image API specification that can be enabled or
disabled through the `iiif.features` section, that is split in multiple subsections. By defaults,
all features (except if otherwise stated) are *enabled*.

### Limits

You can limit the size of images that Wolpi will return to clients through the `iiif.limits` section:

- `max-width`: Maximum width in pixels that images can be returned in
- `max-height`: Maximum height in pixels that images can be returned in
- `max-area`: Maximum area in pixels `(width x height)` that images can be returned in.

### General Features

These settings control general IIIF Image API features that do not fit into other categories and
are all set as to-level entries in the `iiif.features` section:

- `profile-link-header`:   Whether to include the profile link header in responses
- `json-ld-media-type`:    Whether to use JSON-LD media type for responses
- `cors`:                  Whether to set the CORS header to `*`
- `canonical-link-header`: Whether to include a canonical link header in responses
- `canonical-redirect`:    Whether to redirect to the canonical URL for the image
- `base-uri-redirect`:     Whether to the info.json endpoint when accessing the base URI
                           without image parameters or `/info.json` suffix.
### Cropping/Region Features

These settings control the supported syntax for the region component for image requests and
are all set as entries under the `region` subsection in the `iiif.features` section:

- `by-percent`: Whether to allow regions specified by percent with `pct:x,y,w,h`
- `by-pixels`:  Whether to allow regions specified by pixels with `x,y,w,h`
- `square`:     Whether to allow square regions with `square`

### Scaling Features

These settings control the supported syntax for the scaling component for image requests and
are all set as entries under the `scaling` subsection in the `iiif.features` section:

- `by-confined-width-height`: Whether to allow scaling by confined width and height with `!w,h`
- `by-height`:                Whether to allow scaling by height with `,h`
- `by-width`:                 Whether to allow scaling by width with `w,`
- `by-percent`:               Whether to allow scaling by percent with `pct:`
- `allow-upscaling`:          Whether to allow scaling by arbitrary (non-aspect-ratio
                              preserving) dimensions with `w,h`
- `by-arbitrary-dimensions`:  Allow upscaling of images, i.e. scaling to larger sizes than the
                              original
### Rotation Features

These settings control the supported syntax for the rotation component for image requests and
are all set as entries under the `rotation` subsection in the `iiif.features` section:

- `mirroring`:            Whether to allow mirroring of images on the vertical axis with `!`
- `by90-degree-rotation`: Whether to allow 90 degree rotations with `0,90,180,270`
- `arbitrary`:            Whether to allow arbitrary rotations with `arbitrary:angle`

### Supported Qualities

You can limit the supported quality options for image requests through the `iiif.features.qualities`
section.

- `allowed`: List of quality values that are supported, defaults to all qualities from the IIIF
  Image API specification (`color`, `gray`, `bitonal`)
- `default-quality`: Quality value that will be used when the `default` quality is request, must be one
   of the allowed qualities, defaults to `color`

### Supported Image Output Formats

You can limit the image formats that users can request through the `iiif.features.formats` section.

- `allowed`: Lit of image formats (as the [extension string from the IIIF Image
  API spec][iiif-spec-exts], e.g. `jpg` or `png`). Defaults to all formats defined in the IIIF Image API
    specification except `pdf` (`jpg`, `png`, `tif`, `webp`, `gif`, `jp2`)
- `preferred`: List of image formats that will be listed as the preferred formats in the `info.json`
  response. Must be a subset of the allowed formats. Defaults to `jpg`, `png`, `webp`.

[iiif-spec-exts]: https://iiif.io/api/image/3.0/#45-format


### Extension Configuration

For details on how extensions are configured, refer to the [extensions section in the documentation][exts-doc].

[exts-doc]: ./extensions.md/#extension-configuration

## Full example configuration

```yaml
wolpi:
  logging:
    level: info
    format: text
  data-directory: "./data"
  image-base-dir: "./images"
  extensions: []
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
    restrict-to-sizes: false
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

##  👷🪜🕳️ Escape-Hatch: Spring Boot configuration

Wolpi is built on top of [Spring Boot][spring-boot] and you can use any of its [supported configuration
options][spring-boot-config] to further customize your setup.

**Please note:** You do this at your own risk, these settings can in rare cases break certain Wolpi
functionality if set incorrectly or expose you to additional security risks. Make sure you understand
the implications of changing these settings before doing so.

[spring-boot]: https://spring.io/projects/spring-boot
[spring-boot-config]: https://docs.spring.io/spring-boot/appendix/application-properties/index.html