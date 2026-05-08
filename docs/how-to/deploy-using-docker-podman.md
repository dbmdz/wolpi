# Deploying Wolpi Using Docker/Podman

This how-to shows how to run Wolpi in a container with a mounted configuration
file, verify that requests work, and check basic operational signals.

## Prerequisites

- Docker or Podman is installed.
- You have a `wolpi.yml` configuration file.
- If you want to serve your own images: you have a host directory with image files.

## Start Wolpi in a container

Use either Docker or Podman:

```console
$ docker run --rm -p 8080:8080 -v ./wolpi.yml:/app/wolpi.yml ghcr.io/dbmdz/wolpi:latest
```

```console
$ podman run --rm -p 8080:8080 -v ./wolpi.yml:/app/wolpi.yml ghcr.io/dbmdz/wolpi:latest
```

## Verify the container is serving requests

Query the built-in validation image:

```console
$ curl -s http://localhost:8080/v3/67352ccc-d1b0-11e1-89ae-279075081939/info.json | jq '.sizes'
```

Expected result: a JSON array with one size entry (`1000x1000`).

## Mount your own image directory (optional)

By default, Wolpi serves images from `/app/images` in the container. Mount your
host image directory there:

```console
$ docker run --rm -p 8080:8080 \
  -v ./wolpi.yml:/app/wolpi.yml \
  -v /path/to/your/images:/app/images \
  ghcr.io/dbmdz/wolpi:latest
```

If your host has `foo/bar/baz.jpg`, you can request:

```console
$ curl -I http://localhost:8080/v3/foo/bar/baz.jpg/full/!200,200/0/default.jpg
```

If you want to go further than relative paths as identifiers, head over to
["Using an Extension: Pattern Resolver"](../getting-started/using-an-extension-pattern-resolver.md)

## Check health and metrics endpoints

Use these endpoints to confirm the process is healthy and exporting metrics:

```console
$ curl -s http://localhost:8080/monitoring/health/readiness
$ curl -s http://localhost:8080/monitoring/health/liveness
$ curl -s http://localhost:8080/monitoring/prometheus | head
```

## Keep extension state persistent (if needed)

If you [install extensions](./install-extensions.md) from package registries at runtime, persist
`/app/data` so extension artifacts survive container restarts:

```console
$ docker run --rm -p 8080:8080 \
  -v ./wolpi.yml:/app/wolpi.yml \
  -v ./wolpi-data:/app/data \
  ghcr.io/dbmdz/wolpi:latest
```

If you want deterministic startup and no runtime package installation, use a
custom image with pre-installed extensions as described in
[Building containers with pre-installed extensions](./build-containers-with-pre-installed-extensions.md).

## Next steps

- If you want to publish Wolpi under a public hostname, TLS terminator, or path prefix, see
  [Expose Wolpi Behind a Reverse Proxy](./expose-wolpi-behind-a-reverse-proxy.md).
- For extension configuration examples, see [Using Extensions](./install-extensions.md).
- For runtime tuning, see
  [Optimizing Wolpi Configuration](./optimize-the-configuration-for-better-performance.md).

## Container Licensing

The Wolpi container image uses Oracle GraalVM for performance reasons. While the
application code is MIT licensed, the container image itself is subject to the
[GraalVM Free Terms and Conditions][gftc-license], which permits use (including
for commercial purposes) and free redistribution but restricts selling the JDK
distribution. You're fine using the container in production (including
commercial SaaS use), but **if you want to sell the container image itself, you
need to get a commercial GraalVM license from Oracle.**

**tl;dr for 99.99% of all users**: You're fine.

[gftc-license]: https://www.oracle.com/downloads/licenses/graal-free-license.html
