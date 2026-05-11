# CLI

Wolpi can either start its HTTP server normally or run one of a small set of operational
subcommands.

This page documents the built-in CLI surface that is useful for operators and anyone developing or
debugging extensions.

## Invocation

Typical invocations look like:

```console
$ java -jar wolpi.jar --config=/path/to/wolpi.yml <subcommand>
```

or, in the official container image:

```console
$ wolpi <subcommand>
```

If you do not pass a subcommand, Wolpi performs its normal application startup and serves HTTP
requests.

## Running CLI subcommands in the container

When you run a subcommand in the official container image, all paths are resolved inside the
container filesystem, not on the host.

That means:

- the configuration file must be mounted into the container
- any local extension path passed to `validate` must exist inside the container
- if `validate` needs to observe local changes, the extension directory must be mounted, not just
  copied into the image

For the simplest setup, mount your `wolpi.yml` and local extension into the container at the same
paths you use on the host. That keeps command lines, logs, and debugger configuration aligned
between local and container-based workflows.

Example:

```console
$ docker run --rm \
  -v "$(pwd)/wolpi.yml:$(pwd)/wolpi.yml" \
  -v "$(pwd)/my-extension:$(pwd)/my-extension" \
  ghcr.io/dbmdz/wolpi:latest \
  --config="$(pwd)/wolpi.yml" validate "$(pwd)/my-extension"
```

## Configuration loading

CLI subcommands still load the normal Wolpi configuration.

By default, Wolpi looks for `wolpi.yml` or `wolpi.yaml` in the current working directory. To point
at a different configuration file, use:

- `--config=/path/to/wolpi.yml`
- or `WOLPI_CONFIG=/path/to/wolpi.yml`

## Commands overview

### `validate`

Validates a local extension against Wolpi's IIIF Image API validation workflow.

Synopsis:

```console
$ java -jar wolpi.jar --config=/path/to/wolpi.yml validate <extension-path>
```

Options:

- `-w`, `--watch`: watch the extension file or directory and re-run validation on changes

Use this when:

- developing a local extension
- checking whether a local extension still conforms after changes
- running an edit-validate loop during extension development

Notes:

- `validate` expects a local file or directory path
- Wolpi starts its embedded server as part of the validation workflow
- in watch mode, local package live reload has extra prerequisites, please refer to the
  [extension development documentation](../extension-development.md#live-reload-for-local-extensions)
  for details.

Examples:

```console
$ java -jar wolpi.jar validate ./my-extension
$ java -jar wolpi.jar validate --watch ./my-extension
```

### `install-extensions`

Installs and verifies the extensions declared in the active configuration, then exits.

Synopsis:

```console
$ java -jar wolpi.jar --config=/path/to/wolpi.yml install-extensions
```

Use this when you want to:

- pre-install package-based extensions before first service start
- bake extensions into a container image
- fail early in CI or image builds if extension installation or validation breaks

This is usually the most useful operational subcommand for deployments that use extensions, see
the corresponding [How-To guide](../how-to/build-containers-with-pre-installed-extensions.md)
for a practical example of how this subcommand fits into a container-based deployment.

## Related docs

- [Using Extensions](../how-to/install-extensions.md)
- [Building containers with pre-installed extensions](../how-to/build-containers-with-pre-installed-extensions.md)
- [Deploying Wolpi Using the Published JAR](../how-to/deploy-using-the-jar.md)
