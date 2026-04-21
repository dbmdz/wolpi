# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Fixed
- Determining available image sizes from pyramidal TIFs that use SubIFDs to
  encode their layers was broken due to a flipped predicate.
- Encoding into bitonal PNGs and GIFs. Was broken due to a modification of an
  immutable container. We now copy into a mutable container before appending
  our custom option to the default options for the format.

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
