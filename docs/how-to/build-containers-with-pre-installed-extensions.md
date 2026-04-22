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
FROM wolpi:0.1.0

COPY ./my-extension /app/my-extension
COPY config.yml /app/wolpi.yml

RUN java -jar /app/app.jar install-extensions
```

This will then install and validate your extension during the container build, resulting in a
container that is much faster to start up.

!!! warning "Container Licensing"

    The Wolpi container image uses Oracle GraalVM for performance reasons. While the application
    code is MIT Licensed, the container image itself is subject to the [GraalVM Free Terms and
    Conditions][gftc-license], which permits use (including for commercial purposes) and free
    redistribution but restricts selling the JDK distribution. You're fine using the container in
    production (even commercially, i.e. running a SaaS service), but if you want to sell the
    container image itself (e.g. as part of a commercial enterprise container
    subscription service), you need to get a commercial GraalVM license from Oracle.

    [gftc-license]: https://www.oracle.com/downloads/licenses/graal-free-license.html
