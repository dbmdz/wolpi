# HTTP Integration

This reference documents Wolpi's HTTP-facing behavior around endpoint layout, redirects, headers,
and caching. It only covers Wolpi specifics. For the actual IIIF Image API, refer to the
specification for [Version 2][iiif-v2] and [Version 3][iiif-v3].

[iiif-v2]: https://iiif.io/api/image/2.1/
[iiif-v3]: https://iiif.io/api/image/3.0/


## Endpoint overview

Wolpi exposes the IIIF Image API under versioned paths:

- `/{version}/{identifier}`
- `/{version}/{identifier}/info.json`
- `/{version}/{identifier}/{region}/{size}/{rotation}/{quality}.{format}`

Supported `version` values are `v2` and `v3`.

In addition to the IIIF endpoints, Wolpi exposes observability endpoints under `/monitoring`. Those
are documented in the [observability reference](./observability.md).

## CORS

In its default configuration, Wolpi is fully CORS-compliant out of the box, meaning:

- `OPTIONS` pre-flight requests are handled cheaply and return the allowed methods (`GET`, `HEAD`,
  `OPTIONS`) as well as a mirror of the requested headers in the allowed headers.
- `Access-Control-Allow-Origin` is set to the value of the `Origin` header on the request, or to `*`
  if not available, for both `GET/HEAD` and `OPTIONS` requests.

This behavior can be disabled by setting `iiif.features.cors` to `false` in the [configuration][cfg].

[cfg]: ./configuration.md#iiif-configuration

## Redirects

By default, Wolpi will redirect requests in one of two cases:

1. `/{version}/{identifier}` → `/{version}/{identifier}/info.json` (HTTP 303), can be disabled with
   `iiif.features.base-uri-redirect`
2. If necessary, image requests will be redirected to their [canonical form][canonical-form] (HTTP
   301), can be disabled with `iiif.features.canonical-redirect`

In addition to the redirect, if Wolpi can derive a canonical form, image responses will also include
a canonical `Link` header even when no redirect happens (can be disabled with
`iiif.features.canonical-link-header`).

[canonical-form]: https://iiif.io/api/image/3.0/#48-canonical-uri-syntax

## Public URL generation

Wolpi needs to know the public base URL in order to generate correct:

- `id` values in `info.json`
- canonical redirect targets
- canonical `Link` headers

Wolpi determines this from the incoming request by default (`Host` and HTTP request path), using
`X-Forwarded-*` headers if present (i.e. `X-Forwarded-Host`, `X-Forwarded-Proto` and
`X-Forwarded-Prefix`).

If that is not working reliably in your deployment, set `http.base-uri` explicitly to override
the public base URL.

For deployment guidance, see [Expose Wolpi Behind a Reverse Proxy](../how-to/expose-wolpi-behind-a-reverse-proxy.md).

## Content types

### `info.json`

`info.json` responses use:

- `application/ld+json;profile="http://iiif.io/api/image/2/context.json"` for IIIF Image API 2
- `application/ld+json;profile="http://iiif.io/api/image/3/context.json"` for IIIF Image API 3

when `iiif.features.json-ld-media-type` is enabled.

If that feature is disabled, Wolpi returns `application/json`.

### Image responses

Image responses use the media type corresponding to the encoded output format returned by the
processing pipeline or a formatting extension.

## Caching and conditional requests

Wolpi supports regular [HTTP cache validation][http-cache] through:

- `ETag`
- `Last-Modified`
- `If-None-Match`
- `If-Modified-Since`

[http-cache]: https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Caching#validation

### Cache metadata source

Cache metadata (ETag value, modified timestamp) can come from:

- a resolving extension
- the filesystem, for filesystem-backed images when Wolpi can derive cache information from the
  resolved path
- an HTTP-backed source, where Wolpi can forward cache validators from the upstream source response

### `Cache-Control`

Wolpi always sets `Cache-Control` on successful IIIF responses using:

- `cache-control-headers.info-json` for `info.json`
- `cache-control-headers.images` for image responses

The default for both is:

- `public, max-age=604800, must-revalidate`

These values are configured through the
[`cache-control-headers` section in the configuration reference](./configuration.md#http-caching-headers).

### `304 Not Modified`

If the client's validators match the current source state, Wolpi returns `304 Not Modified` and
skips the expensive parts of request handling.

This can happen for both `info.json` and image requests.

## Related reference

- [Configuration](./configuration.md) for `http`, `iiif`, and cache-control settings
- [Observability](./observability.md) for health endpoints, metrics, and logging
