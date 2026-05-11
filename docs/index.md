![wolpi](./img/wolpi_notext.png){ width=200, align=left }

# Wolpi: An Extensible IIIF Image Server
Wolpi is a [libvips][vips]-based IIIF Image API (2.1 and 3.0) compliant image server written in Java.
It  is from the ground up designed to be easily extensible and customizable in order to fit into
different use cases and workflows.

[vips]: https://www.libvips.org/

## Configuration
Through the YAML-based configuration, you can customize various aspects of Wolpi's behavior, such as:

- vips image encoding options (e.g. JPEG quality, WebP presets, etc.)
- enable/disable optional IIIF Image API features and limits, to tailor Wolpi to your use cases, the
  IIIF  Image API compliance level and `info.json` will be automatically adjusted accordingly

Refer to the [configuration documentation][config-doc] to learn more about the available configuration
options.

[config-doc]: ./reference/configuration.md

## Extending Wolpi
Wolpi can be extended using custom extensions written in JavaScript or Python. These extensions can
be used to customize various aspects of Wolpi's behavior (in ways that do not violate the IIIF
Image API specification):

- Authorization (e.g. to integrate with your organization's [IIIF Authorization Flow API][auth]
  implementation)
- Resolving, with the option to include image metadata from an external source to avoid hitting
  the filesystem for `info.json` requests
- Augment the `info.json` response, e.g. to include reference to [additional IIIF services][extra-services]
- Image operations, to implement custom image transformations or filters that go beyond the IIIF
  Image API specification:
    * Preprocess images (e.g. for watermarking)
    * Override/extend cropping, scaling, rotation and quality transformations (e.g. to implement
      custom smart cropping algorithms or image filters)
    * Implement custom output formats or customize the encoding of existing formats

Refer to the [extension usage documentation][exts-usage-doc] to learn how to install and configure
existing Wolpi extensions. To learn how to write your own extensions, refer to the
[extension development documentation][exts-dev-doc].

[auth]: https://iiif.io/api/auth/2.0/
[extra-services]: https://iiif.io/api/image/3.0/#58-linking-properties
[exts-usage-doc]: ./how-to/install-extensions.md
[exts-dev-doc]: ./extension-development.md

## Operating Wolpi

Wolpi provides everything required to run it both on traditional servers and in cloud environments:

- Runnable as a [standalone JAR][ops-jar] or [OCI container][ops-container]
- Full support for [HTTP caching semantics][ops-caching] (ETags, Last-Modified, Cache-Control, etc.)
  to easily  integrate with CDNs and proxy caches
- [Health checks][ops-health] and [metrics endpoint for monitoring][ops-metrics]
- [Structured logging][ops-logging] to the standard output (to be picked up by e.g. journald or k8s)

Most of the above features are integrated into the extension API, so custom extensions can
[log][ops-extdev-log], [export metrics][ops-extdev-metrics], and provide cache metadata for
resolved images as well.

[ops-jar]: ./how-to/deploy-using-the-jar.md
[ops-container]: ./how-to/deploy-using-docker-podman.md
[ops-caching]: ./reference/http.md#caching-and-conditional-requests
[ops-health]: ./reference/observability.md#health-endpoints
[ops-metrics]: ./reference/observability.md#built-in-wolpi-metrics
[ops-logging]: ./reference/observability.md#structured-logging
[ops-extdev-log]: ./extension-development.md#logging-from-extensions
[ops-extdev-metrics]: ./extension-development.md#custom-metrics-from-extensions
