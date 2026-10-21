# Expose Wolpi Behind a Reverse Proxy

This guide covers the Wolpi-specific things you need to get right when exposing Wolpi behind a
reverse proxy, ingress controller, or API gateway.

It does not provide full proxy configuration examples, since the exact syntax depends on the proxy
you use. The goal here is to make sure Wolpi generates correct public URLs, redirects, and IIIF
`id` values, and that multi-instance deployments route requests for good cache locality.

## Forward the public request information

If Wolpi is reachable through a public URL that differs from its local bind address, your proxy
must forward the public request information correctly.

In practice, that means:

- preserve the public `Host` header, or forward the equivalent host information
- forward the relevant `X-Forwarded-*` headers:
  `X-Forwarded-For`: client IP that initiated the request
  `X-Forwarded-Host`: original host that the request was made to
  `X-Forwarded-Proto`: original protocol (`http` or `https`) that the request was made with
  `X-Forwarded-Prefix`: prefix Wolpi is hosted on, for example `/iiif/image` if Wolpi is accessed
  through `https://your.tld/iiif/image/v3/some_id/info.json`

This matters because Wolpi derives public-facing URLs from the incoming request when possible. That
includes generated `id` values in `info.json` responses and canonical redirect targets.

!!! warning
    For `X-Forwarded-For` **make sure that clients cannot set it themselves** if you are using an
    extension that does IP-based authorization.

## Fallback: Set `http.base-uri` when header forwarding is not reliable

If your proxy cannot forward the public request information correctly, set `http.base-uri`
explicitly in `wolpi.yml`:

```yaml
http:
  base-uri: https://images.example.org/iiif
```

Use the full public base URL that clients should see. Do not end it with a trailing slash.

This is the safest option when:

- you do not control the proxy configuration
- multiple proxy layers rewrite request headers in inconsistent ways
- Wolpi is mounted below a public path prefix and the forwarded prefix handling is unreliable

## Limit the public surface

For most deployments, only the IIIF API should be reachable from outside the local or cluster
network.

If your proxy supports path-based routing, expose only requests matching `^/v[23]/.+` publicly.
Keep `/monitoring/*` internal by default and only expose it deliberately if you have a specific
reason and separate access controls.

This reduces accidental exposure of operational endpoints and makes the public trust boundary
clearer.

## Route requests by image identifier

When serving HTTP image sources from multiple Wolpi instances, give each instance its own local
source cache and route by image identifier instead of sharing a disk cache. Send `info.json` and
all tiles and transformations of the same image to the same instance so they reuse its cached
source file. This applies to containers, JAR processes, and Kubernetes pods.

Configure your reverse proxy, ingress controller, or gateway as follows. The exact settings depend
on the proxy you use:

1. Extract the image identifier from the IIIF path. Use the same key across IIIF versions and
   exclude `info.json`, region, size, rotation, quality, and format. Include a stable tenant or
   namespace if identifiers are not globally unique.
2. Choose a healthy backend using a stable, well-distributed hash of that key. Prefer consistent
   hashing with virtual nodes, or rendezvous hashing, to spread identifiers across instances and
   limit reassignment when instances join or leave. Weight placement by capacity if backends differ.
3. Send traffic to the selected instance without another load-balancing hop choosing a different
   backend. Neither client-IP affinity nor hashing the complete request URL gives all requests for
   an image the same routing key.
4. Check the proxy's upstream logs: requests for one identifier's `info.json` and several tile
   URLs should reach the same instance while the backend set stays unchanged. Repeat with many
   different identifiers to check that placement spreads across the instances. Then remove or add
   an instance and verify that reassigned requests succeed through the new instance's cold cache.

Hashing distributes identifiers, not necessarily equal amounts of traffic. One popular image can
still overload its assigned instance; downstream caching of IIIF responses can reduce that load.
Failover or scaling may move an identifier to another instance, which warms its own cache without
access to the previous instance's files. Routing affects cache efficiency, not correctness.

For cache limits, freshness settings, and diagnostics, see the
[HTTP source cache configuration](../reference/configuration.md#http-source-cache). For Kubernetes
endpoint selection and per-pod storage, see the
[Kubernetes deployment guide](./deploy-on-kubernetes.md#route-requests-to-pod-endpoints).

## Verify the public URL behavior

After exposing Wolpi through the proxy, verify the externally visible behavior from the public URL,
not from the internal address.

### Check `info.json`

Request a public `info.json` URL and confirm that the `id` field uses the public base URL and, if
applicable, the public path prefix.

If the `id` points to an internal hostname, internal port, or misses the public path prefix, your
forwarded headers are incomplete or `http.base-uri` is not set correctly.

### Check canonical redirects

Request an image URL that should redirect to the [canonical form of the request][canonical-form] and
inspect the `Location` header.

The redirect target should use the public scheme, host, and path prefix. If it redirects to an
internal address or drops the prefix, fix the forwarded headers or set `http.base-uri`.

[canonical-form]: https://iiif.io/api/image/3.0/#48-canonical-uri-syntax

## Put access logging at the proxy layer

If you need HTTP access logs, the reverse proxy is usually the right place to collect them.

Wolpi's own structured application logs are useful for application behavior, but proxy or gateway
logs are usually a better source for request-level access auditing, client IP handling, and public
URL visibility.

## Related reference

See the [configuration reference](../reference/configuration.md#http-configuration) for
`http.base-uri`, the [HTTP integration reference](../reference/http.md) for redirects, caching, and
response semantics, and the [observability reference](../reference/observability.md) for health,
metrics, and application logging.
