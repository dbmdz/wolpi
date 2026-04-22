# Building containers with pre-installed extensions

For container-based deployments with extensions, it can make sense to create a
custom image based on the official Wolpi image, where all the extensions are
already pre-installed. This will significantly reduce the time until requests
can be served, reduce the risk of running into unexpected situations when the
container is started, as well as make it possible to restrict egress traffic for
the container (since no package indices need to be accessed to install the
extensions).

To do so, you have to:

1. Copy your extension(s) into the container
2. Copy a configuration file that references those extensions to `/app/wolpi.yaml`
3. Run Wolpi with the `install-extensions` subcommand during the container build

Here is an example that builds an image with a single pre-installed local packaged extension:

```yaml title="config.yml"
extensions:
  - path: /app/my-extension
```

```docker title="Dockerfile.customized"
FROM ghcr.io/dbmdz/wolpi:0.1.2

COPY ./my-extension /app/my-extension
COPY config.yml /app/wolpi.yml

RUN java -jar /app/app.jar install-extensions
```

This will then install and validate your extension during the container build, resulting in a
container that is much faster to start up.

If you intend to redistribute this modified container as part of a commercial offering, please be
aware of the [licensing situation of the container image](./deploy-using-docker-podman.md#container-licensing).
