# Monitoring Wolpi

## Health Endpoints

Wolpi exposes health endpoints compatible with Kubernetes and other orchestration systems at the
`/monitoring/health` (for overall health), `/monitoring/health/liveness` (for liveness probes) and
`/monitoring/health/readiness` (for readiness probes) endpoints by default. These endpoints can be
used to monitor the health of the  Wolpi instance and ensure that it is running correctly.

Currently there is no way to hook into the health checks from extensions, but this may be added
in the future, let us know in a GitHub issue if you need this functionality.

## Metrics

To allow for monitoring of its runtime behavior, Wolpi exposes a set of [Prometheus][prometheus]
metrics that can be used to build dashboards and create alerts.
Additionally, [extensions can provide their own metrics][extension-metrics] to augment those exposed
by Wolpi itself.

By default, the metrics are exposed at `/monitoring/prometheus`:

```
$ curl http://localhost:8080/monitoring/prometheus
# HELP application_ready_time_seconds Time taken for the application to be ready to service requests
# TYPE application_ready_time_seconds gauge
application_ready_time_seconds{main_application_class="dev.mdz.wolpi.Wolpi"} 3.682
# HELP application_started_time_seconds Time taken to start the application
# TYPE application_started_time_seconds gauge
application_started_time_seconds{main_application_class="dev.mdz.wolpi.Wolpi"} 3.67
[...]
```

Refer to the [metrics reference][metrics-reference] for a complete list of exposed metrics and their documentation.

[prometheus]: https://prometheus.io
[extension-metrics]: ../extension-development.md#custom-metrics-from-extensions
[metrics-reference]: ../reference/metrics.md

## Structured Logging

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
[wolpi-spring-cfg]: ../reference/configuration.md
[extensions-log]: ../extension-development.md#logging-from-extensions
