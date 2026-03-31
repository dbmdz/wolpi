# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Fixed
- Image size mismatches with JP2 sources by using the rounding method in
  libvips/openjpeg (→ ceil division for odd dimensions)

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
