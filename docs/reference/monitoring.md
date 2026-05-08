# Monitoring and Logging

Wolpi exposes a small built-in observability surface for health checks, metrics, and structured
application logging.

## Health endpoints

By default, Wolpi exposes the following health endpoints under the `/monitoring` base path:

- `/monitoring/health`: overall health status
- `/monitoring/health/liveness`: liveness probe endpoint
- `/monitoring/health/readiness`: readiness probe endpoint

These endpoints are compatible with common orchestration environments such as Kubernetes.

## Metrics endpoint

Wolpi exposes Prometheus-format metrics at:

- `/monitoring/prometheus`

This endpoint includes:

- built-in JVM / Spring Boot / Micrometer metrics
- built-in Wolpi-specific metrics
- custom metrics registered by extensions

For the built-in Wolpi metrics, see the [metrics reference](./metrics.md).

!!! question "But I don't use Prometheus?"

    Wolpi only exposes Prometheus-format metrics directly, but since it is built on Micrometer
    you can configure other exporters such as OTLP or DataDog through
    [Spring Boot configuration][spring-boot-config].
    Put those settings under the `spring` section of your `wolpi.yml` and refer to the
    [Spring Boot metrics exporter documentation][spring-boot-docs].

## Structured logging

Wolpi always logs to standard output.

To enable structured JSON logging, set:

```yaml
logging:
  level: info
  format: json
```

This switches console output to one JSON object per line. The structured output includes fields such
as:

- `@timestamp`
- `level`
- `logger_name`
- `message`
- `thread_name`

[Additional structured key/value pairs passed to the logger](../extension-development.md#logging-from-extensions)
are emitted as separate JSON fields.

## Access logs

Wolpi does not provide built-in access logs in its normal application log output.

If you need access logs, the preferred approach is to run Wolpi behind a reverse proxy or API
gateway that provides access logging.

For the Wolpi-specific reverse-proxy considerations, see
[Expose Wolpi Behind a Reverse Proxy](../how-to/expose-wolpi-behind-a-reverse-proxy.md).

If you need local access logs from Wolpi itself, you can configure [Tomcat access logging][tomcat-access-logging]
through the [Spring Boot configuration][spring-boot-config]. These logs are separate from Wolpi's
normal structured/text application logs and can only be written to a file, not standard output.

[spring-boot-config]: ./configuration.md#-escape-hatch-spring-boot-configuration
[spring-boot-docs]: https://docs.spring.io/spring-boot/reference/actuator/metrics.html
[tomcat-access-logging]: https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.configure-access-logs
