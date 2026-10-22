# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Changed
- **Breaking:** Wolpi now refuses to start when a configured extension cannot be loaded, instead
  of logging the error and starting without it. An extension that is configured but not loaded no
  longer enforces its hooks, so e.g. an `authorize` extension that failed to load left the server
  publicly accessible. The startup error lists every failing extension together with the position
  in the extension code that caused the failure. The `validate` and `install-extensions`
  subcommands are exempt, they log the failure and continue with the remaining extensions.
- **Breaking:** The container image now runs as the unprivileged user 
  (UID/GID `10001`) by default. Existing bind mounts or volumes for `/app/data`
  that are owned by root must be writable by this user (or, in Kubernetes,
  use `securityContext.fsGroup: 10001`). Mounted configuration and image files must
  be readable by this user. The default user can be changed.

### Added
- Shared `http-timeouts.{connect,request}` settings (defaults `10s`/`30s`), with optional
  `extensions` and `sources` overrides to differentiate between HTTP requests made by Wolpi itself
  and those made by extensions through the `httpClient` on the context or the JavaScript
  `wolpi:fetchSync` function

### Removed
- Wolpi is no longer published to Maven Central or the Sonatype OSS Snapshot
  Maven Repository, GitHub Releases and the GitHub Container Registry remain as
  the sole distribution channels. This is due to Maven Central's draconic
  limits that were introduced in October 2026, but it also serves to discourage
  inheriting from Wolpi in other Java projects, since the Extension API is
  intended as the sole officially supported means of customizing Wolpi.


### Fixed
- An interrupted request thread no longer spins in the parallel `authorize` and `resolve` hook
  loops. The remaining extension calls are cancelled and the request fails with an error response
  instead.
- Requesting WebP images with either dimension larger than 16,383 pixels now
  results in a HTTP 400 response instead of a HTTP 500 response.
- Credentials for a custom PyPI `index` are no longer passed to pip on the
  command line and are masked in logs. Packages are now resolved only from the
  custom index, no longer from both the custom index and PyPI.
- URL-encoded `/` in identifiers are now properly supported, this was
  documented as working before, but was actually broken due to a bad Tomcat
  default setting

## [0.3.0] - 2026-06-29

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
