# Observability

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

- built-in Wolpi metrics
- custom metrics registered by extensions
- selected JVM / Spring Boot metrics

Requests to `/monitoring/*` are excluded from the standard `http_server_requests` metric so that
health checks and metrics scraping do not distort application request latency metrics.

!!! question "But I don't use Prometheus?"

    Wolpi exposes Prometheus-format metrics directly. If you want to export metrics to other
    backends such as OTLP or Datadog, configure the corresponding Spring Boot exporter under the
    `spring` section of your `wolpi.yml` and refer to the
    [Spring Boot metrics exporter documentation][spring-boot-docs].

If you have [defined custom metrics in your extensions][extensions-metrics], those are exposed at
the same endpoint automatically.

## Built-in Wolpi metrics

### `wolpi_images_processed_total`

- Type: Counter
- Measures: Number of images successfully processed and encoded
- Tags:
  - `format`: Output format such as `jpg`, `png`, or `webp`
  - `quality`: IIIF quality such as `color`, `gray`, or `bitonal`
  - `iiif_version`: Requested IIIF Image API version, `v2` or `v3`

This counter increments after a successful image response.

For performance analysis, this metric is most useful as a throughput breakdown. Compare it with
`wolpi_image_processing_seconds` to see which output formats or quality modes dominate request
volume and whether slow requests are simply the most common requests.

### `wolpi_image_processing_seconds`

- Type: Timer
- Measures: End-to-end time spent in Wolpi's built-in image processing and encoding pipeline for a
  successful image request
- Tags:
  - `format`: Output format such as `jpg`, `png`, or `webp`
  - `output_size`: Bucketed output size based on the largest output dimension:
    `tiny` (up to 256), `small` (up to 512), `medium` (up to 1024), `large` (up to 2048),
    `xlarge` (up to 4096), `huge` (above 4096), `unknown`
  - `cropped_area`: Bucketed crop area:
    `tiny` (up to 256x256), `small` (up to 512x512), `medium` (up to 1024x1024),
    `large` (up to 2048x2048), `xlarge` (up to 4096x4096), `huge`, `unknown`
  - `request_type`: Heuristic request class:
    `tile`, `thumbnail`, `full`, `other`
  - `scale_crop_mode`: Processing strategy:
    `scale_no_crop`, `scale_then_crop`, `crop_then_scale`

This timer records how long Wolpi spends processing and encoding successful image responses.

The tags are intended to support performance investigation:

- `output_size` separates small thumbnail-style work from large full-resolution work.
- `cropped_area` shows whether cost is driven by large crop windows even when the final output is
  small.
- `request_type` helps distinguish common access patterns such as tiled viewers versus thumbnail
  grids.
- `scale_crop_mode` reveals which internal processing path dominates. `scale_no_crop` is usually
  the cheapest path, `crop_then_scale` is the most general path, and `scale_then_crop` is an
  optimization that is mainly helpful for untiled formats.

If this metric is slow overall, break it down by `request_type`, then by `output_size` and
`scale_crop_mode` before tuning image encoding or extension pool settings.

### `wolpi_source_loads_total`

- Type: Counter
- Measures: Number of source image loads by source type and load strategy
- Tags:
  - `source_type`: Source category such as a filesystem-backed or HTTP-backed source
  - `load_type`: Load strategy, one of `open`, `thumbnail`, or `shrink_on_load`

This counter tracks where Wolpi loads source images from and which load path was used.

For performance analysis, `load_type` is the important dimension:

- `open`: Full open with no load-time scaling
- `thumbnail`: Load-time scaling through libvips thumbnail support
- `shrink_on_load`: Loader-level downscaling for suitable pyramidal formats when the requested size
  matches an available reduced resolution

High `open` rates for workloads dominated by small outputs can indicate missed opportunities for
more efficient source loading.

### `wolpi_vips_errors_total`

- Type: Counter
- Measures: Number of libvips-related processing errors
- Tags:
  - `context`: Processing context in which the error occurred

This is the primary built-in error metric for failures in native image processing operations.

Use `context` to distinguish where failures occur, for example during image processing rather than
source loading or metadata extraction. A rising error rate here should usually be correlated with
application logs.

### `wolpi_validation_requests_total`

- Type: Counter
- Measures: Number of IIIF validation test image requests
- Tags: none

This metric is mainly relevant while validating Wolpi or extensions against the IIIF validation
suite. It is usually not operationally important in normal production traffic.

### `wolpi_extension_invocations_total`

- Type: Counter
- Measures: Number of extension hook invocations
- Tags:
  - `extension_name`: Extension name as returned by the extension's `info` hook
  - `hook_type`: Invoked hook name

This counter increments every time Wolpi calls an extension hook.

Use it as the volume companion to `wolpi_extension_execution_seconds`. A slow hook with a very low
invocation rate is a different problem from a moderately slow hook that runs on every request.

### `wolpi_extension_execution_seconds`

- Type: Timer
- Measures: Time spent executing extension hooks
- Tags:
  - `extension_name`: Extension name as returned by the extension's `info` hook
  - `hook_type`: Executed hook name

This timer helps identify slow hooks and slow extensions.

For performance analysis:

- `extension_name` shows which extension contributes latency.
- `hook_type` shows where in the extension API the time is spent, such as resolving, metadata
  loading, or image processing hooks.

If request latency is high and this timer is a major contributor, optimize the extension itself or
increase extension pool capacity if the bottleneck is concurrency rather than per-call cost.

### `wolpi_extension_errors_total`

- Type: Counter
- Measures: Number of extension hook errors
- Tags:
  - `extension_name`: Extension name as returned by the extension's `info` hook
  - `hook_type`: Hook that raised or propagated an error

This is the primary built-in error metric for extension execution.

Track it together with `wolpi_extension_invocations_total` to distinguish a high absolute error
count on a busy hook from a genuinely high failure rate.

### `wolpi_extensions_loaded`

- Type: Gauge
- Measures: Number of configured extensions currently loaded
- Tags: none

This gauge is registered once the application is fully started. It is mainly useful as a sanity
check that the expected set of configured extensions was loaded.

### `wolpi_extensions_pool`

- Type: Gauge
- Measures: Current extension runtime pool state per extension
- Tags:
  - `extension_name`: Extension name as returned by the extension's `info` hook
  - `state`: Pool state, one of `active`, `idle`, or `client_waiting`

This gauge reports the live state of the extension runtime pool for each extension.

The `state` tag is the key performance dimension:

- `active`: Contexts currently borrowed and processing work
- `idle`: Contexts currently available for reuse
- `client_waiting`: Request-handling threads currently blocked waiting for a context

For performance analysis, sustained non-zero `client_waiting` is the clearest sign of pool
exhaustion. High `active` with near-zero `idle` during peak traffic usually means the pool is
running at its concurrency limit.

### `wolpi_extensions_pool_events`

- Type: Counter
- Measures: Cumulative extension runtime pool lifecycle events per extension
- Tags:
  - `extension_name`: Extension name as returned by the extension's `info` hook
  - `event`: Pool lifecycle event, one of `created`, `destroyed`, `borrowed`, or `returned`

This counter tracks how the extension runtime pool changes over time.

The `event` tag supports different questions:

- `created`: How often Wolpi has to create new contexts
- `destroyed`: How often contexts are evicted or torn down
- `borrowed`: How often work is handed to the pool
- `returned`: How often contexts finish work and go back to idle

For performance analysis:

- `created` should usually stabilize after warm-up.
- A rising `created` rate during steady-state traffic suggests `min-idle` is too low or eviction is
  too aggressive.
- High `destroyed` rates suggest churn and avoidable startup overhead.
- `borrowed` and `returned` are useful as throughput counters for extension-backed work.

See the [extension pool configuration documentation][pool-config] for details on the relevant pool
settings.

## Example Prometheus queries

```promql
# Pool utilization (percentage of contexts in use)
wolpi_extensions_pool{state="active"} /
  (wolpi_extensions_pool{state="active"} + wolpi_extensions_pool{state="idle"}) * 100

# Rate of context creation (contexts/sec) - should be near zero after warm-up
rate(wolpi_extensions_pool_events{event="created"}[5m])

# Contexts created but not yet destroyed (should match active + idle)
wolpi_extensions_pool_events{event="created"} - wolpi_extensions_pool_events{event="destroyed"}
```

## Structured logging

Wolpi always logs to standard output.

To enable structured JSON logging, set:

```yaml
logging:
  level: info
  format: json
```

This switches console output to one JSON object per line.

The structured output includes core fields such as:

- `@timestamp`
- `level`
- `logger_name`
- `message`

When applicable, it also includes structured stack-trace and request-correlation fields such as:

- `stack_trace`
- `stack_hash`
- `request_id`

[Additional structured key/value pairs passed to the logger](../extension-development.md#logging-from-extensions)
are emitted as separate JSON fields.

## Access logs

Wolpi does not provide built-in access logs in its normal application log output.

If you need access logs, the preferred approach is to run Wolpi behind a reverse proxy or API
gateway that provides access logging.

For the Wolpi-specific reverse-proxy considerations, see
[Expose Wolpi Behind a Reverse Proxy](../how-to/expose-wolpi-behind-a-reverse-proxy.md).

For cache headers, redirects, CORS, and other IIIF-facing HTTP behavior, see the
[HTTP integration reference](./http.md).

If you need local access logs from Wolpi itself, you can configure [Tomcat access logging][tomcat-access-logging]
through the [Spring Boot configuration][spring-boot-config]. These logs are separate from Wolpi's
normal structured/text application logs and can only be written to a file, not standard output.

## Related reference

- [Configuration](./configuration.md)
- [HTTP Integration](./http.md)

[spring-boot-config]: ./configuration.md#spring-boot-escape-hatch
[spring-boot-docs]: https://docs.spring.io/spring-boot/reference/actuator/metrics.html
[tomcat-access-logging]: https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.configure-access-logs
[pool-config]: ../how-to/optimize-the-configuration-for-better-performance.md#extension-pool-configuration
[extensions-metrics]: ../extension-development.md#custom-metrics-from-extensions
