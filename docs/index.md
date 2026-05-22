<p align="center">
  <img src="./img/wolpi.png" alt="Wolpi" width="420">
</p>

# Wolpi: An Extensible IIIF Image Server

Wolpi is a [libvips][vips]-based IIIF Image API (2.1 and 3.0) compliant image server.
It is from the ground up designed to be easily extensible and customizable in order to fit into
different use cases and workflows.

Use it to serve images through standard IIIF URLs, with enough configuration and extension hooks to
adapt it to local storage, metadata systems, authorization, custom image processing and operational
monitoring.

[vips]: https://www.libvips.org/

## Quickstart

To get your feet wet with Wolpi, we recommend you start with our tutorials:

- [Serve your first image](./getting-started/serving-your-first-image.md).
- Use an extension to [resolve images regular expressions](./getting-started/using-an-extension-pattern-resolver.md).
- Deploy Wolpi on a server, via [Docker/Podman](./how-to/deploy-using-docker-podman.md),
  bare-metal using [the published JAR](./how-to/deploy-using-the-jar.md), or in your
  [Kubernetes cluster](./how-to/deploy-on-kubernetes.md).
- Configuring Wolpi: use the [configuration reference](./reference/configuration.md).
- Writing extensions: read [Developing Extensions](./extension-development.md).

## Configuration

The YAML-based configuration allows you to customize various aspects of Wolpi's behavior, such as:

- [vips image encoding options], e.g. JPEG quality, WebP presets, etc.
- [optional IIIF Image API features and limits], the IIIF Image API compliance level and `info.json`
  response are adjusted accordingly.
- Operational options like [HTTP settings] and [logging behavior]
- Extensions and their runtime options

Refer to the [configuration documentation][config-doc] to learn more about the available
configuration options.

[config-doc]: ./reference/configuration.md
[vips image encoding options]: ./reference/configuration.md#image-encoding-options
[optional IIIF Image API features and limits]: ./reference/configuration.md#iiif-configuration
[HTTP settings]: ./reference/configuration.md#http-configuration
[logging behavior]: ./reference/configuration.md#logging-configuration

## Extending Wolpi

Wolpi can be extended using custom extensions written in JavaScript or Python. These extensions can
be used to customize various aspects of Wolpi's behavior in ways that do not violate the IIIF Image
API specification:

- Authorization, e.g. to integrate with your organization's [IIIF Authorization Flow API][auth]
  implementation.
- Resolving identifiers to image sources, with the option to include image metadata from an external
  source to avoid hitting the filesystem for `info.json` requests.
- Augmenting the `info.json` response, e.g. to include references to
  [additional IIIF services][extra-services].
- Image operations that go beyond the IIIF Image API specification, such as watermarking, smart
  cropping, custom filters, custom output formats or custom encoding for existing formats.

Refer to the [extension usage documentation][exts-usage-doc] to learn how to install and configure
existing Wolpi extensions. To learn how to write your own extensions, refer to the
[extension development documentation][exts-dev-doc].

[auth]: https://iiif.io/api/auth/2.0/
[extra-services]: https://iiif.io/api/image/3.0/#58-linking-properties
[exts-usage-doc]: ./how-to/install-extensions.md
[exts-dev-doc]: ./extension-development.md

## Operating Wolpi

Wolpi provides everything required to run it both on traditional servers and in cloud environments:

- Runnable as a [standalone JAR][ops-jar], [OCI container][ops-container], or
  [Kubernetes deployment][ops-k8s].
- Full support for [HTTP caching semantics][ops-caching] (ETags, Last-Modified, Cache-Control, etc.)
  to easily integrate with CDNs and proxy caches.
- [Health checks][ops-health], [Prometheus metrics][ops-metrics] and
  [structured logging][ops-logging].

Most of the above features are integrated into the extension API, so custom extensions can
[log][ops-extdev-log], [export metrics][ops-extdev-metrics], and provide cache metadata for
resolved images as well.

[ops-jar]: ./how-to/deploy-using-the-jar.md
[ops-container]: ./how-to/deploy-using-docker-podman.md
[ops-k8s]: ./how-to/deploy-on-kubernetes.md
[ops-caching]: ./reference/http.md#caching-and-conditional-requests
[ops-health]: ./reference/observability.md#health-endpoints
[ops-metrics]: ./reference/observability.md#built-in-wolpi-metrics
[ops-logging]: ./reference/observability.md#structured-logging
[ops-extdev-log]: ./extension-development.md#logging-from-extensions
[ops-extdev-metrics]: ./extension-development.md#custom-metrics-from-extensions
