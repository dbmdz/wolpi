# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

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
