<p align="center">
  <img src="./docs/img/wolpi.png" alt="Wolpi" width="420">
</p>

# Wolpi: A fast and extensible IIIF Image Server

Wolpi is a [libvips][vips]-based IIIF Image API (2.1 and 3.0) compliant image server written in
Java. It is from the ground up designed to be easily extensible and customizable in order to fit into
different use cases and workflows.

The documentation lives at [wolpi.mdz.dev][docs]. Start there if you want to deploy Wolpi, use
extensions, or understand how request and image processing works internally.

[docs]: https://wolpi.mdz.dev
[vips]: https://www.libvips.org/

## Development Requirements

- `libvips` must be available on the system
- Java must be installed in at least version 25
- Maven must be installed for building and testing
- `npm` is needed when developing or installing JavaScript extensions
- GraalPy is needed when developing or installing Python extensions

This repository includes configuration for [Mise](https://mise.jdx.dev) that can install the Java, Node.js and GraalPy toolchain used
for local development:

```sh
mise install
```

You still need to install `libvips` through your operating system package manager.

## Quick Start

```sh
mkdir -p images
cp docs/img/wolpi.png images/
mvn spring-boot:run
```

In a different shell:

```sh
curl -v http://localhost:8080/v3/wolpi.png/info.json
```

You should be able to open http://localhost:8080/v3/wolpi.png/full/max/0/default.webp in your browser.

For a container-based first run, see ["Serving your first image"][first-image] in the documentation.

[first-image]: https://wolpi.mdz.dev/getting-started/serving-your-first-image

## Extensions

Wolpi can be extended with custom logic written in JavaScript or Python. Extensions can be used for
authorization, resolving identifiers to image sources, augmenting `info.json`, custom image
processing, custom output formats, logging, metrics and cache metadata.

To use existing extensions, see [Using Extensions][using-extensions]. To learn how to create your own
extensions, see the [extension development documentation][extension-development].

[using-extensions]: https://wolpi.mdz.dev/how-to/install-extensions
[extension-development]: https://wolpi.mdz.dev/extension-development

## Developing Wolpi

After installing the requirements, the main development loop is Maven-based:

```sh
# Compile and run Spotless checks
mvn compile

# Run the full test suite
mvn test

# Apply Java formatting
mvn spotless:apply

# Package the application
mvn package
```

Wolpi reads its defaults from [`src/main/resources/application.yml`][application-yml]. During local
development, the default image base directory is `./images` and the extension data directory is
`./data`.

Useful local endpoints:

- IIIF image information: http://localhost:8080/v3/wolpi.png/info.json
- IIIF image request: http://localhost:8080/v3/wolpi.png/full/max/0/default.webp
- Health: http://localhost:8080/monitoring/health
- Prometheus metrics: http://localhost:8080/monitoring/prometheus

[application-yml]: ./src/main/resources/application.yml

## Building the Documentation

Use [Zensical][zensical] to build or serve the documentation.

```bash
# install uv if you don't have it already
# curl -LsSf https://astral.sh/uv/install.sh | sh

# serve the docs locally
uv run --with zensical zensical serve

# build the docs into the `public` directory
uv run --with zensical zensical build --clean
```

The documentation entry point is [`docs/index.md`](./docs/index.md), and the navigation is configured
in [`mkdocs.yml`](./mkdocs.yml).

[zensical]: https://zensical.org/docs/
