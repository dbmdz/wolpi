![](./docs/img/wolpi.png){width=50%}
# Wolpi: A fast and extensible IIIF Image Server

## Requirements
- `libvips` must be available on the system
- Java must be installed in at least version 24

## Quick Start

```sh
$ cp -R docs/img images
$ mvn spring-boot:run
# In a different shell
$ curl -v http://localhost:8080/v3/wolpi.png/info.json
```

You should be able to open http://localhost:8080/v3/wolpi.png/full/max/0/default.webp in your browser.

## Extensions

Wolpi can be extended with custom logic written in JavaScript or Python. To learn more about how to create extensions, see the [extension documentation](./docs/extensions.md).

## Building the documentation

Use `mkdocs` with the `mkdocs-material` plugin to build or serve the documentation:

```bash
# install uv if you don't have it already
# curl -LsSf https://astral.sh/uv/install.sh | sh
# serve the docs at localhost:8000/wolpi
uv run --with mkdocs-material mkdocs serve
# build the docs into the `site` directory
uv run --with mkdocs-material mkdocs build
```

## Troubleshooting

- **Application fails to start due to missing vips shared library:** The `vips-ffm` bindings are pretty picky
  in terms of how they locate the shared library. If your shared library name is not ending on `.so`, it won't
  be found. You can either create a symlink to e.g. `/lib/x86_64-linux-gnu/libvips.so`, or you can invoke
  Wolpi with `-Dvipfsffm.libpath.vips.override=/lib/x86_64-linux-gnu/libvips.so.42`. You can find out where
  your vips library is located by invoking `ldd $(which vips) |grep libvips`.