# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added
- New config option `image-processing.binarization-method` to select the method
  used for converting to the `bitonal` quality. Currently supported methods are
  `otsu` and `dither`. The implementation is public in
  `dev.mdz.wolpi.image.Binarization` so extension authors can implement dynamic
  strategies for determining the best method.

## [0.2.1] - 2026-06-26

### Added
- HTTP image sources now relay upstream caching headers (`ETag`,
  `Last-Modified`) to Wolpi clients. This enables proper conditional
  request round-trips for S3 and other HTTP-backed image sources.

### Fixed
- Extensions returning `tileSizes` or other collection-typed fields in their
  resolving data no longer cause a `ClassCastException`. The
  `RecordValueMapper` now converts parameterized `List<T>` members element
  by element, preventing raw polyglot proxy lists from escaping into type-
  specific iteration code.
- Creating sRGB output images from 1-bit source images now correctly sets the
  light color to pure white, instead of dark gray
- Creating bitonal output images from 1-bit source images now correctly reproduces
  the source image instead of being either all-black (unscaled) or inverted with
  halos (downscaled).


## [0.2.0] - 2026-06-04

### Added
- Container images now include a `wolpi` launcher script that wraps the
  required JVM options.
- Documentation for reverse proxy deployment, HTTP integration, CLI usage,
  observability, and Wolpi's request/image processing pipeline.
- Mise configuration for the local Java, Node.js, and GraalPy development
  toolchain.

### Changed
- Public documentation and project metadata now point to `https://wolpi.mdz.dev`.
- The documentation set has been reorganized into tutorials, how-to guides,
  concepts, and reference material.
- Docker images now use the `wolpi` launcher for startup and validator
  installation.
- CLI validation errors are now reported without full stack traces.
- Updated `app.photofox.vips-ffm:vips-ffm-core` to `1.9.8`.
- Updated `com.diffplug.spotless:spotless-maven-plugin` to `3.5.1`.
- Quality configuration now supports wildcard patterns (e.g. `*` or
  `ai:*`) in the `iiif.qualities.allowed` list, matched via the new
  `Qualities.allows()` and `Qualities.isWildcard()` helpers.
- ETag headers in image info and image responses now use the weak
  validator prefix (`W/`), enabling conditional revalidation behind
  cache layers that require weak validators.
- Enabled GraalVM `js.text-encoding=true` option to make `TextEncoder`
  and `TextDecoder` APIs available to JavaScript-based extensions.

### Removed
- `CustomSourceResolvedImage` for resolved images with callback-based
  methods for providing data. Upon further testing this proved infeasible
  due to the thread affinity of Graal Polyglot Contexts, which can only
  be used from a single thread. In Wolpi, Contexts are owned by the thread
  handling the request. However, vips executes its image loading on a
  separate thread, and it is from this thread that it also calls the callback
  methods in the context, which Graal prevents. We thus were left
  with no choice but to remove this.

### Fixed
- Determining available image sizes from pyramidal TIFs that use SubIFDs to
  encode their layers was broken due to a flipped predicate.
- Reloaded Python file extensions now reparse changed source files instead of
  reusing stale GraalPy source cache entries.
- Reloaded extensions now remove all previous hook registrations for the same
  configuration before registering the replacement hooks.
- Encoding into bitonal PNGs and GIFs. Was broken due to a modification of an
  immutable container. We now copy into a mutable container before appending
  our custom option to the default options for the format.
- Python `skippableHooks` values now accept enum names and documented hook
  aliases, including snake_case and camelCase spellings.
- Class-based Python extensions now discover implemented hooks in Python,
  including the documented `wolpi_extension()` factory path and hooks inherited
  from user-defined base classes.
- Redirects and canonical links now consistently use the configured
  `http.base-uri`.
- `info.json` responses now use the image base URL, rather than the
  `info.json` URL itself, as their identifier.
- JavaScript `skippableHooks` implementations can now access request records
  through the same proxy conversion used by other extension hook calls.
- JavaScript extension registry authentication now works with the default npm
  registry when no custom registry URL is configured.

### Security
- Prevent path traversal attacks through the fallback image resolver
  by making sure the final resolved path is actually inside the configured base
  directory

## [0.1.2] - 2026-04-21

### Added
- `skippableHooks`, a performance hint API for extensions to declare which
  hooks can be skipped for a request

### Fixed
- Image size mismatches with JP2 sources by using the rounding method in
  libvips/openjpeg (→ ceil division for odd dimensions)
- JavaScript extensions can now return class-based objects, not just plain
  object literals
- Installing JavaScript extensions from local directories now explicitly sets
  `install-links=false` to avoid npm-version-dependent behavior
- Extension logger names are now prefixed with `dev.mdz.wolpi` so configured
  logging rules apply consistently
- Filesystem-resolved images now get `cacheInfo` populated automatically
  when extensions omit it, enabling conditional requests based on file
  metadata
- HTTP error responses from remote image sources are now passed through to
  clients instead of being reported as HTTP 500 errors, and conditional
  requests against HTTP-resolved images now forward `304 Not Modified`
  responses and cache validators correctly

### Changed
- Updated `org.springdoc:springdoc-openapi-starter-webmvc-api` to `3.0.3`

## [0.1.1] - 2026-03-31

### Fixed
- Out-Of-Bounds Error when using `thumbnailSource` on HTTP sources
- Image size mismatches during image processing due to inconsistent rounding
  methods
- HTTP 500 when a JPEG image with at least one dimension >= 65536 was
  requested, now results in a HTTP 400

## [0.1.0] - 2026-03-30

### Added
- Initial Release
