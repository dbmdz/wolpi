# Expose Wolpi Behind a Reverse Proxy

This guide covers the Wolpi-specific things you need to get right when exposing Wolpi behind a
reverse proxy, ingress controller, or API gateway.

It does not provide full proxy configuration examples, since the exact syntax depends on the proxy
you use. The goal here is to make sure Wolpi generates correct public URLs, redirects, and IIIF
`id` values.

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
