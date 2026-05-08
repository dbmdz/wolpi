# Deploying Wolpi Using the Published JAR

While we recommend running Wolpi in a container environment (
i.e. [Docker/Podman](./deploy-using-docker-podman.md) or
even [Kubernetes](./deploy-on-kubernetes.md)), it's totally possible
to run it directly from the published JAR available on [GitHub Releases][gh-releases].

This guide will show you the dependencies you need to have available in your
environment, how to configure Wolpi and then how to run it through
systemd.

The paths we selected in this guide are merely a suggestion, feel free to
adapt to your environment. The examples assume you are running Debian GNU/Linux,
but the same overall process also works on other Linux distributions with equivalent packages and
service management.

[gh-releases]: https://github.com/dbmdz/wolpi/releases

## Prerequisites

- You have a Linux host with `systemd` as your PID1.
- You have internet access to download all required dependencies
- You can install OS packages and create users and groups as `root`.

## Install dependencies

You can use any Java 25 JVM to run Wolpi. If you use extensions, we
**highly recommend** you use the [GraalVM Enterprise Edition][graal-ee] Java runtime,
which comes with some welcome optimizations for the GraalJS and GraalPy runtimes
used by Wolpi to execute extensions.

**Wolpi requires a recent [`libvips`][libvips] installation** on the host. Use
version 8.18 or newer, if possible. If your distribution ships an older version, we recommend
using a newer package source before continuing. Older versions lack an API that allows accessing
the tile sizes for the `info.json` responses, so if you use an older version, the `tiles`
field will be missing, even if the input image supports tiled access.

**We highly recommend** not using the default memory allocator on glibc-based systems (most common
Linux distributions use glibc), since it is prone to memory fragmentation for the types of workloads
Wolpi executes. A good choice is [jemalloc2][jemalloc], which is available on most distributions.
This guide will assumes you have jemalloc2 available on the system.

**If you use JavaScript extensions**, install a [recent Node.js and npm release][node-npm].
Use at least `npm` 10; a current Node.js LTS release is a good default.

**If you use Python extensions**, install the standalone [GraalPy runtime][graalpy], whose
major version matches the GraalVM version used by Wolpi. At the time of writing, that means
[**GraalPy 25**][graalpy-25].

[graal-ee]: https://www.graalvm.org/downloads/
[node-npm]: https://nodejs.org/en/download
[graalpy]: https://www.graalvm.org/python/python-developers/
[graalpy-25]: https://github.com/oracle/graalpython/releases/
[libvips]: https://www.libvips.org/
[memory-fragmentation]: https://www.libvips.org/API/8.18/developer-checklist.html#linux-memory-allocator
[jemalloc]: https://github.com/jemalloc/jemalloc

## Set up the runtime environment for Wolpi

Wolpi should not be run as root, so create a dedicated system user for it, with
a home directory where it can put its runtime data.

```console
$ sudo useradd --system --home /var/lib/wolpi --shell /usr/sbin/nologin wolpi
```

Create the directories for the Wolpi JAR and the configuration
```console
$ sudo install -d -o root -g root /opt/wolpi
$ sudo install -d -o root -g root /etc/wolpi
```

## Download the published JAR

Download a specific Wolpi release and place it under `/opt/wolpi`:

```console
$ sudo wget -O /opt/wolpi/wolpi-<version>.jar \
  https://github.com/dbmdz/wolpi/releases/download/<version>/wolpi-<version>.jar
$ sudo ln -sfn /opt/wolpi/wolpi-<version>.jar /opt/wolpi/wolpi.jar
```

Replace `<version>` with the release you want to deploy. We create a symlink so we can update
Wolpi more easily later on.

## Create the configuration file

Create `/etc/wolpi/wolpi.yml`:

```yaml title="/etc/wolpi/wolpi.yml"
data-directory: "/var/lib/wolpi/data"

http:
  host: "0.0.0.0"
  port: 8080

packaging:
  # Set these if the executables are not on the default PATH used by systemd:
  # npm-executable: "/opt/npm/bin/npm"
  # python-executable: "/opt/graalpy/bin/graalpy"
```

If you want to install extensions, add them under `extensions:` as described
in [Using Extensions](./install-extensions.md). You might also want to check
out the [optimization guide](./optimize-the-configuration-for-better-performance.md)
for the available knobs.

## Install extensions before first start (optional)

If your configuration includes package-based extensions, install and verify them once
before starting the service:

```console
$ sudo -u wolpi /path/to/your/java/bin/java -jar /opt/wolpi/wolpi.jar \
  --config=/etc/wolpi/wolpi.yml install-extensions
```

If you installed GraalPy, make sure `packaging.python-executable` points to `graalpy`, not to
CPython.

## Create the systemd service
To manage the service, we're going to add a systemd service, which will allow
us to easily start/restart/stop the service and read the logs.

For this, create `/etc/systemd/system/wolpi.service`:

```systemd title="/etc/systemd/system/wolpi.service"
[Unit]
Description=Wolpi IIIF Image Server
After=network.target

[Service]
Type=exec
User=wolpi
Group=wolpi
WorkingDirectory=/var/lib/wolpi

Environment="JAVA_HOME=/path/to/your/java"
Environment="PATH=/usr/local/bin:/usr/bin:/bin"
Environment="VIPS_CONCURRENCY=1"
Environment="LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libjemalloc.so.2"

ExecStart=/path/to/your/java/bin/java \
    --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow \
    -jar /opt/wolpi/wolpi.jar --config=/etc/wolpi/wolpi.yml

SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Replace `/path/to/your/java` with your actual Java installation path.

`VIPS_CONCURRENCY=1` is a good production default because it avoids CPU
oversubscription when Wolpi processes several requests at once.

If `libvips` is installed in a non-standard library location and Wolpi cannot
find it, add the necessary `-Dvipsffm.libpath.*` JVM flags to `ExecStart`.

## Start Wolpi

Reload `systemd`, enable the service, and start it:

```console
$ sudo systemctl daemon-reload
$ sudo systemctl enable --now wolpi
```

## Verify the deployment

Check that the service is up:

```console
$ systemctl status wolpi
```

Verify Wolpi's readiness endpoint:

```console
$ curl -s http://127.0.0.1:8080/monitoring/health/readiness
```

Expected result: a JSON response with `"status":"UP"`.

You can also verify that image requests work by querying the built-in validation
image on the public port:

```console
$ curl -s http://127.0.0.1:8080/v3/67352ccc-d1b0-11e1-89ae-279075081939/info.json | jq '.sizes'
```

## Next steps

- If you want to publish Wolpi behind a public web server, ingress, or TLS terminator, see
  [Expose Wolpi Behind a Reverse Proxy](./expose-wolpi-behind-a-reverse-proxy.md).
- For extension configuration examples, see [Using Extensions](./install-extensions.md).
- For container-based deployments, see [Deploying Wolpi Using Docker/Podman](./deploy-using-docker-podman.md).
- For runtime tuning, see [Optimizing Wolpi Configuration](./optimize-the-configuration-for-better-performance.md).
