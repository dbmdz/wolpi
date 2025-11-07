![wolpi](./img/wolpi_notext.png){ width=200, align=left }

# wolpi: an extensible iiif server
Wolpi is a [libvips][vips]-based IIIF Image API (2.1 and 3.0) compliant image server written in Java.
It  is from the ground up designed to be easily extensible and customizable in order to fit into
different use cases and workflows.

At its core, Wolpi is simply a IIIF Image API 2.1 and 3.0 server that can serve images from a
directory on the local filesystem. However, it can be extended with custom extensions written in
JavaScript or Python to customize its behavior at all steps of the image delivery process, from auth
and resolving to the individual image operations and even the output encoding.

[vips]: https://www.libvips.org/

## Running Wolpi
The easiest way to run Wolpi is to use the provided Docker image:

```bash
docker run -p 8080:8080 -v <path_to_your_images>:/images ghcr.io/dbmdz/wolpi:latest
```

By default, images will then be served from the `/images` directory inside the container, e.g. an
image located  at `<path_to_your_images>/foo/bar/baz.jpg` on the host machine will be available at
`http://localhost:8080/iiif/3/foo/bar/baz.jpg`.

## Configuration
Through the YAML-based configuration, you can customize various aspects of Wolpi's behavior, such as:
- vips image encoding options (e.g. JPEG quality, WebP presets, etc)
- enable/disable optional IIIF Image API features and limits, to tailor Wolpi to your use cases, the
  IIIF  Image API compliance level and `info.json` will be automatically adjusted according

Refer to the [configuration documentation][config-doc] to learn more about the available configuration
options.

[config-doc]: ./configuration.md

## Extending Wolpi
Wolpi can be extended using custom extensions written in JavaScript or Python. These extensions can
be used to customize various aspects of Wolpi's behavior (in ways that do not violate the IIIF
Image API specification):

- Authorization (e.g. to integrate with your organization's [IIIF Authorization Flow API][auth])
  implementation
- Resolving, with the option to include image metadata from an external source to avoid hitting
  the filesystem for `info.json` requests
- Augment the `info.json` response, e.g. to include reference to [additional IIIF services][extra-services]
- Image operations, to implement custom image transformations or filters that go beyond the IIIF
  Image API specification:
    * Preprocess images before they are processed (e.g. for watermarking)
    * Override/extend cropping, scaling, rotation and quality transformations (e.g. to implement
      custom smart cropping algorithms or image filters)
    * Implement custom output formats or customize the encodinging of existing formats

Refer to the [extension documentation][exts-doc] to learn how to install, configure and write
your own Wolpi extensions.

[auth]: https://iiif.io/api/auth/2.0/
[extra-services]: https://iiif.io/api/image/3.0/#58-linking-properties
[exts-doc]: ./extensions.md

## Operating Wolpi

Wolpi provides all the necessities to run it both on traditional servers and in cloud environments:

- Runnable as a standalone JAR or OCI container
- Full support for HTTP caching semantics (ETags, Last-Modified, Cache-Control, etc.) to easily
  integrate with CDNs and proxy caches
- Health checks and metrics endpoint for monitoring (from K8s or systemd)
- Structured logging to the standard output (to be picked up by e.g. journald or k8s)

Most of the above features are integrated into the extension API, so you can log, export metrics and
provide caching information from your custom extensions as well.