# Optimizing the configuration for better performance

## Extension Pool Configuration

Wolpi runs extensions from a pool of cached extension instances to improve performance (essential, especially with Python
extensions, where the initialization can take upwards of a second). You can configure the behavior of this pool through the
`extension-pool` section.

**Why is this important?** GraalVM/GraalPy contexts require expensive JIT compilation when first created. Keeping
contexts warm in the pool avoids this compilation overhead on every request, dramatically improving performance.

Available configuration options:

- `min-idle`: Minimum number of idle extension contexts to keep warm in the pool **per extension**. Defaults to
  the number of logical CPU cores on your system. These contexts are kept alive indefinitely to avoid expensive
  recompilation of extension code. Set this based on your expected average concurrent load.

- `max-idle`: Maximum number of idle extension contexts to keep in the pool **per extension**, defaults to 2*`minIdle`.
  When the pool has more than `min-idle` idle contexts, they will be evicted after `eviction-timeout` (see below).
  Set this to your expected peak concurrent load to avoid blocking requests during traffic spikes.

- `max-total`: Maximum number of total extension contexts (idle + in-use) to keep in the pool **per extension**,
  defaults to 2*`maxIdle`. When this limit is reached, new requests will block until a context becomes available. Set this
  to your absolute maximum concurrent requests, but keep in mind that more contexts means more memory usage.

- `eviction-timeout`: Duration after which idle contexts above `min-idle` will be evicted from the pool, defaults
  to 30 minutes. This helps free up memory when load decreases, while keeping at least `min-idle` contexts warm
  to handle subsequent requests without expensive recompilation overhead.

**Example configuration:**

```yaml
extension-pool:
  min-idle: 8        # Keep 8 contexts always warm
  max-idle: 16       # Allow up to 16 idle contexts during peaks
  max-total: 32      # Allow up to 32 total contexts (idle + active)
  eviction-timeout: 1h  # Evict idle contexts above min-idle after 1 hour
```

**Performance tip:** For high-traffic deployments, set `min-idle` equal to your typical concurrent request count
to ensure contexts are always warm and ready. For memory-constrained environments, use a lower `min-idle` value
and rely on the pool's ability to grow dynamically up to `max-total`.

For more information about the extension pool and lifecycle, refer to the [section in the extension documentation](../extension-development.md#extension-lifecycle).

## Caching

Wolpi fully supports HTTP caching semantics to make it easy to run it behind CDNs and reverse
proxies. It automatically generates and handles `ETag`/`If-None-Match`, `Last-Modified`/
`If-Modified-Since` headers and Cache-Control directives for all image responses, based on the
underlying image file's metadata or the data provided by custom resolvers.

If you want local caching for source images (e.g. if your images come from a remote endpoint, and
you want a disk-based cache), you can implement this in a custom extension that handles the caching
logic in the `resolve` hook (e.g. by returning the path to the cache file instead of a HTTP URL).
