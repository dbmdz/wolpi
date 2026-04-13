# Metrics

Wolpi exposes various metrics about its operation, performance and resource usage in the
[Prometheus](https://prometheus.io/) format at the `/monitoring/prometheus` endpoint by default.
This includes metrics about HTTP requests, response times, memory and CPU usage, as well as
various internal metrics about image processing operations.

If you have [defined custom metrics in your extensions][extensions-metrics], those will also be
exposed at this endpoint automatically.

## Extension Pool Metrics

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
```

**Tuning Tips**:

- If `pool_waiting` is frequently non-zero, increase `max-total` to allow more concurrent contexts.
  This is a trade-off, since more contexts use more memory
- If `created` keeps increasing during normal operation, your `min-idle` is too low or
  `eviction-timeout` is too short
- Monitor `active` during peak load to set appropriate `min-idle` and `max-idle` values
- High `destroyed` rate indicates aggressive eviction - consider increasing `eviction-timeout`

See the [extension pool configuration documentation][pool-config] for details on these parameters.

[pool-config]: ../how-to/optimize-the-configuration-for-better-performance.md#extension-pool-configuration

!!! question "But I don't use Prometheus?"

    While Wolpi by default only exposes metrics in the Prometheus format, you can configure it to
    expose metrics in any format supported by [Micrometer][micrometer] like OTLP or DataDog by
    setting a few Spring Boot configuration options. Refer to the [Spring Boot
    documentation][spring-boot-docs] for details on how to configure different metrics exporters.
    You can then put those values into the [`spring` section of your configuration][wolpi-spring-cfg].

[micrometer]: https://docs.micrometer.io/micrometer/reference/implementations.html
[spring-boot-docs]: https://docs.spring.io/spring-boot/reference/actuator/metrics.html
[wolpi-spring-cfg]: ./configuration.md
[extensions-metrics]: ../extension-development.md#custom-metrics-from-extensions
