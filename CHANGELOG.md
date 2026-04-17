# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

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
  clients instead of being reported as HTTP 500 errors

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
