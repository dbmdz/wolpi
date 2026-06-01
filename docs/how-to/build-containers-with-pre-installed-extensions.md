# Building containers with pre-installed extensions

For container-based deployments with extensions, it can make sense to create a
custom image based on the official Wolpi image, where all the extensions are
already pre-installed. This will significantly reduce the time until requests
can be served, reduce the risk of running into unexpected situations when the
container is started, as well as make it possible to restrict egress traffic for
the container (since no package indices need to be accessed to install the
extensions).

## Prerequisites

- Docker or Podman is installed.
- You know the extension package and version you want to pre-install.
- If you use packages from remote indices: Network access to the package index is available during
  image build.

## Building the customized container

1. Copy a configuration file that references the extensions you want to bake in to `/app/wolpi.yml`
2. If you use local extensions: Copy those to the configured path inside the container
3. Run Wolpi with the `install-extensions` subcommand during the container build

For the exact behavior of `install-extensions` and the related CLI commands, see the
[CLI reference](../reference/cli.md).

Here is an example that builds an image with the [Pattern Resolver extension][pattern-resolver]
that is being installed remotely from [npmjs.com][npmjs]:

[pattern-resolver]: https://github.com/dbmdz/wolpi-pattern-resolver
[npmjs]: https://www.npmjs.com/

```yaml title="build-config.yml"
extensions:
  - npm:
      pkg: "wolpi-pattern-resolver"
      version: "0.1.0"
```

If you use a local extension, specify its path **inside the container** in the configuration
and then copy it to that location in the Dockerfile.

```docker title="Dockerfile.customized"
FROM ghcr.io/dbmdz/wolpi:latest

COPY build-config.yml /app/wolpi.yml
# copy your local extensions to the configured path here, if you have them
# COPY ./my-extension /app/my-extension

RUN wolpi install-extensions
```

```console
$ docker build . -f Dockerfile.customized -t ghcr.io/dbmdz/wolpi:latest-custom
STEP 1/3: FROM ghcr.io/dbmdz/wolpi:latest
STEP 2/3: COPY build-config.yml /app/wolpi.yml
--> b48822d2be79
STEP 3/3: RUN wolpi install-extensions
  Ѱ Ѱ
 (\_/)
ʚ(•.•)ɞ     ~ wolpi ~
 (")(")    0.1.2
16:44:31.431 💬 ExtensionRegistry Extension 'Pattern Resolver' loaded.
16:44:32.303 💬 ValidatingRunner Starting validation of 1 extension(s) (0 cached, 1 to validate)
16:44:32.303 💬 ValidatingRunner Validating extension 'Pattern Resolver'...
16:44:32.303 💬 ImageApiValidator Running IIIF Image API validation tests with extension 'Pattern Resolver' against localhost:8080...
16:44:32.304 💬 ImageApiValidator Installing iiif-validator-ng package for IIIF Image API validation...
16:44:53.791 💬 ImageApiValidator Discovering available IIIF Image API validation tests...
16:45:21.581 💬 ValidatingRunner Extension validation successful for all 1 registered extension(s).
16:45:21.582 💬 CliRunner Extensions installed and validated successfully.
COMMIT ghcr.io/dbmdz/wolpi:latest-custom
--> 87365bb009f9
Successfully tagged ghcr.io/dbmdz/wolpi:latest-custom
87365bb009f978970326bfebf67a5bb09fb3f2a98b8998500cab337861658678
```

Note that we did not specify any configuration for the extension here. This is intentional,
since changes to extension configuration should not require a rebuild of the container. So
when you run the container, just create a `wolpi.yml` that keeps the extension in the exact
version that the container was built with and include your configuration with it:

```yaml title="config.yml"
extensions:
  - npm:
      pkg: "wolpi-pattern-resolver"
      version: "0.1.0"
    config:
      resolvingPatterns:
        - pattern: '^([a-z0-9-]+)$'
          substitutions:
            - 'https://my.company.tld/data/images/$1'
```

## Run and test the custom container

Then start the container and observe the logs to verify that the container starts without running
extension installation and serves requests immediately:

```console
$ docker run --rm -p 8080:8080 -v ./config.yml:/app/wolpi.yml ghcr.io/dbmdz/wolpi:latest-custom
  Ѱ Ѱ
 (\_/)
ʚ(•.•)ɞ     ~ wolpi ~
 (")(")    0.1.2
16:48:50.093 💬 ExtensionRegistry Extension 'Pattern Resolver' loaded.
16:48:50.858 💬 ValidatingRunner All 1 extension(s) have been validated previously, skipping validation
16:48:50.859 💬 ValidatingRunner Listening on localhost:8080
```

!!! note
    If you intend to redistribute customized containers like this as part of a commercial offering,
    please be aware of the [licensing situation of the container image](./deploy-using-docker-podman.md#container-licensing).
