# Operating

## Running Wolpi

=== "Standalone JAR"
    This assumes you have an up-to-date (we recommend Java 25) Java Runtime Environment installed.
    If you are using extensions, we *highly* recommend using the [GraalVM distribution of Java][graal-jdk]
    for  best performance with your extensions.

    ```bash
    # Download the latest JAR from the releases page
    wget https://github.com/dbmdz/wolpi/releases/download/0.1.0/wolpi-0.1.0.jar
    # With the configuration file at ./wolpi.yaml
    java -jar wolpi-0.1.0.jar
    # With a custom configuration file
    java -jar wolpi-0.1.0.jar --config=/etc/wolpi/wolpi.yaml
    ```

[graal-jdk]: https://www.graalvm.org/downloads/

=== "Docker/Podman"

    This assumes you have a `wolpi.yaml` configuration file in the current directory. Add additional
    volume mounts as needed to provide access to your images and extensions.

    ```bash
    docker run -p 8080:8080 -v ./wolpi.yaml:/app/wolpi.yaml wolpi:0.1.0
    podman run -p 8080:8080 -v ./wolpi.yaml:/app/wolpi.yaml wolpi:0.1.0
    ```

=== "Kubernetes"

    You can deploy Wolpi in Kubernetes using the following example manifest, which adds a `Deployment`
    and a `Service` to expose it within the cluster. To expose it outside the cluster, you can add an
    `Ingress` or `Gateway` + `HTTPRoute` resource depending on your setup.

    ```yaml title="wolpi-k8s.yaml"
    apiVersion: v1
    kind: ConfigMap
    metadata:
      name: wolpi-config
    data:
      wolpi.yaml: |
        http:
            host: 0.0.0.0
            port: 8080
        logging:
          level: info
          format: json
          log-request-details-on-crash: false
    ---
    apiVersion: apps/v1
    kind: Deployment
    metadata:
      name: wolpi
    spec:
      replicas: 1
      selector:
        matchLabels:
          app: wolpi
      template:
        metadata:
          labels:
            app: wolpi
        spec:
          containers:
            - name: wolpi
              image: wolpi:0.1.0
              imagePullPolicy: IfNotPresent
              ports:
                - containerPort: 8080
                  name: http
              volumeMounts:
                # Add a mount for your images here, if needed
                - name: wolpi-config
                  mountPath: /app/wolpi.yaml
                  subPath: wolpi.yaml
              env:
                - name: JAVA_TOOL_OPTIONS
                  # Use at most 50% of the container memory for the JVM heap, and start with 12.5%
                  # This is needed since libvips uses off-heap memory that also needs to fit into the
                  # container's memory limits
                  value: "-XX:MaxRAMPercentage=50.0 -XX:InitialRAMPercentage=12.5"
              resources:
                requests:
                  memory: "1Gi"
                  cpu: "4"
                limits:
                  # Wolpi by itself doesn't need a lot of memory since vips loads images in a
                  # streaming fashion, adjust this based on your metrics and expected maximum number
                  # of concurrent requests
                  memory: "4Gi"
                  # Adjust CPU limits according to your expected load and available hardware
                  cpu: "8"
              livenessProbe:
                httpGet:
                  path: /monitoring/health/liveness
                  port: http
                initialDelaySeconds: 30
                periodSeconds: 10
              readinessProbe:
                httpGet:
                  path: /monitoring/health/readiness
                  port: http
                initialDelaySeconds: 10
                periodSeconds: 5
          volumes:
            - name: wolpi-config
              configMap:
                name: wolpi-config
    ---
    apiVersion: v1
    kind: Service
    metadata:
      name: wolpi
    spec:
      selector:
        app: wolpi
      ports:
        - protocol: TCP
          port: 80
          targetPort: http
    ```

=== "systemd"

    This assumes you have an up-to-date (we recommend Java 25) Java Runtime Environment installed.
    If you are using extensions, we *highly* recommend using the [GraalVM distribution of Java][graal-jdk]
    for  best performance with your extensions. 

    We also recommend creating a dedicated user for running Wolpi, e.g. `wolpi` to improve security.

    This systemd unit file assumes you have downloaded the Wolpi JAR to `/opt/wolpi/wolpi.jar` and
    have your configuration file at `/etc/wolpi/wolpi.yaml`.

    ```systemd title="wolpi.service"
    [Unit]
    Description=Wolpi IIIF Image Server
    After=network.target

    [Service]
    Type=exec
    # The flags are only to silence some warnings caused by the use of the Graal Polyglot API
    ExecStart=/usr/bin/java \
        --enable-native-access=ALL-UNNAMED \
        --sun-misc-unsafe-memory-access=allow \
        -jar /opt/wolpi/wolpi.jar --config=/etc/wolpi/wolpi.yaml
    WorkingDirectory=/opt/wolpi
    SuccessExitStatus=143

    [Install]
    WantedBy=multi-user.target
    ```

## Customizing the Wolpi container

For container-based deployments, it can make sense to create a custom image based on the official
Wolpi image, where all the extensions are already pre-installed. This will significantly reduce the
time until requests can be served, reduce the risk of running into unexpected situations when the
container is started, as well as make it possible to restrict egress traffic for the container (since
no package indices need to be accessed to install the extensions).

To do so, you have to:

- Copy your extension(s) into the container
- Copy a configuration file that references those extensions to `/app/wolpi.yaml`
- Run Wolpi with the `install-extensions` subcommand during the container build

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
    container image itself (e.g. as part of a commercial enterprise subscription service), you need
    to get a commercial GraalVM license from Oracle.

    [gftc-license]: https://www.oracle.com/downloads/licenses/graal-free-license.html


## Monitoring Wolpi

### Health Endpoints

Wolpi exposes health endpoints compatible with Kubernetes and other orchestration systems at the
`/monitoring/health` (for overall health), `/monitoring/health/liveness` (for liveness probes) and
`/monitoring/health/readiness` (for readiness probes) endpoints by default. These endpoints can be
used to monitor the health of the  Wolpi instance and ensure that it is running correctly.

Currently there is no way to hook into the health checks from extensions, but this may be added
in the future, let us know in a GitHub issue if you need this functionality.

### Metrics

Wolpi exposes various metrics about its operation, performance and resource usage in the
[Prometheus](https://prometheus.io/) format at the `/monitoring/prometheus` endpoint by default.
This includes metrics about HTTP requests, response times, memory and CPU usage, as well as
various internal metrics about image processing operations.

If you have [defined custom metrics in your extensions][extensions-metrics], those will also be
exposed at this endpoint automatically.

#### Extension Pool Metrics

Understanding the behavior of the extension context pool is crucial for tuning performance,
especially for Python extension that have significant startup overhead due to the large
standard library.  Wolpi exposes the following metrics to monitor pool behavior:

**Gauge Metrics** (current state):

- `wolpi_extensions_pool{extension_name="...", state="active"}`: Number of contexts currently
  borrowed from the pool and processing requests
- `wolpi_extensions_pool{extension_name="...", state="idle"}`: Number of contexts available
  in the pool, ready to handle requests without initialization overhead
- `wolpi_extensions_pool{extension_name="...", state="client_waiting"}`: Number of threads currently waiting
  for a context to become available (indicates pool exhaustion)

**Counter Metrics** (cumulative totals):

- `wolpi_extensions_pool_events{extension_name="...", event="created"}`: Total number of contexts created since
  startup. Ideally this should stabilize after initial warm-up.
- `wolpi_extensions_pool_events{extension_name="...", event="destroyed"}`: Total number of contexts destroyed.
  Frequent destruction indicates pool eviction or scaling down.
- `wolpi_extensions_pool_events{extension_name="...", event="borrowed"}`: Total number of times a context was
  borrowed from the pool.
- `wolpi_extensions_pool_events{extension_name="...", event="returned"}`: Total number of times a context was
  returned to the pool after processing a request.

**Example Prometheus Queries**:

```promql
# Pool utilization (percentage of contexts in use)
wolpi_extensions_pool{state="active"} /
  (wolpi_extensions_pool{state="active"} + wolpi_extensions_pool{state="idle"}) * 100

# Rate of context creation (contexts/sec) - should be near zero after warm-up
rate(wolpi_extensions_pool_events{event="created"}[5m])

# Contexts created but not yet destroyed (should match active + idle)
wolpi_extensions_pool_events{event="created"} - wolpi_extensions_pool_events{event="destroyed"}

# Average pool wait time when exhausted (requires wolpi.extension.pool_wait.seconds histogram)
rate(wolpi_extension_pool_wait_seconds_sum[5m]) /
  rate(wolpi_extension_pool_wait_seconds_count[5m])
```

**Tuning Tips**:

- If `pool_waiting` is frequently non-zero, increase `max-total` to allow more concurrent contexts.
  This is a trade-off, since more contexts use more memory
- If `created` keeps increasing during normal operation, your `min-idle` is too low or
  `eviction-timeout` is too short
- Monitor `active` during peak load to set appropriate `min-idle` and `max-idle` values
- High `destroyed` rate indicates aggressive eviction - consider increasing `eviction-timeout`

See the [extension pool configuration documentation][pool-config] for details on these parameters.

[pool-config]: ./configuration.md#extension-pool-configuration

!!! question "But I don't use Prometheus?"

    While Wolpi by default only exposes metrics in the Prometheus format, you can configure it to
    expose metrics in any format supported by [Micrometer][micrometer] like OTLP or DataDog by
    setting a few Spring Boot configuration options. Refer to the [Spring Boot
    documentation][spring-boot-docs] for details on how to configure different metrics exporters.
    You can then put those values into the [`spring` section of your configuration][wolpi-spring-cfg].

[micrometer]: https://docs.micrometer.io/micrometer/reference/implementations.html
[spring-boot-docs]: https://docs.spring.io/spring-boot/reference/actuator/metrics.html
[wolpi-spring-cfg]: ./configuration.md#escape-hatch-spring-boot-configuration
[extensions-metrics]: ./extensions.md#custom-metrics-from-extensions

### Structured Logging

If you have an environment where structured logging is used, you can configure Wolpi to log in
JSON format by setting the `logging.format` configuration option to `json`:

```yaml
logging:
  level: info
  format: json
```

This will log all messages in JSON format, making it easier to parse and analyze logs in
log aggregation systems like ELK or Loki/Grafana.

An example log line in JSON format looks like this:

```json
{
  "@timestamp": "2025-12-11T13:31:42.472044653+01:00",
  "@version": "1",
  "message": "Listening on localhost:8080",
  "logger_name": "dev.mdz.wolpi.validation.ValidatingRunner",
  "thread_name": "main",
  "level": "INFO",
  "level_value": 20000
}
```

Any extra key/value pairs passed to the logger (e.g. [from extensions][extensions-log]) will
also be included as separate fields in the JSON object.

!!! question "Access Logs?"

    Wolpi does not provide access logs by default, since it's intended to be run behind a reverse proxy
    or API gateway that handles access logging much better. If you need access logs, consider
    using e.g. Nginx or Traefik in front of Wolpi to provide this functionality. If you really
    need access logs from Wolpi itself, you can use the Spring Boot configuration options for
    [Tomcat Access Logging][tomcat-access-logging]. Enable it via the [`spring` section of your
    configuration.][wolpi-spring-cfg]. Note that this will log into a separate file, detached from
    the regular logging system.

[tomcat-access-logging]: https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.configure-access-logs
[wolpi-spring-cfg]: ./configuration.md#escape-hatch-spring-boot-configuration
[extensions-log]: ./extensions.md#logging-from-extensions


## Caching

Wolpi fully supports HTTP caching semantics to make it easy to run it behind CDNs and reverse
proxies. It automatically generates and handles `ETag`/`If-None-Match`, `Last-Modified`/
`If-Modified-Since` headers and Cache-Control directives for all image responses, based on the
underlying image file's metadata or the data provided by custom resolvers.

If you want local caching for source images (e.g. if your images come from a remote endpoint, and
you want a disk-based cache), you can implement this in a custom extension that handles the caching
logic in the `resolve` hook (e.g. by returning the path to the cache file instead of a HTTP URL).
