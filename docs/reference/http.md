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

If the client's validators match the current source state, Wolpi returns `304 Not Modified` for
both `info.json` and image requests. Resolver-supplied validators take precedence over validators
Wolpi learns from the source. Both `200` and `304` responses use the same weak ETags and configured
client-facing cache policy.

Wolpi does not forward client conditions to HTTP origins. It uses stored origin validators to
check cached source bytes instead. Without a cached body, Wolpi may need to download the source
before returning a client `304`, even with source caching disabled. An origin `304` without a
stored body cannot satisfy an image load and produces HTTP `502`.

## HTTP source caching

Wolpi caches complete HTTP source images as local files so different tile requests can reuse one
download. It also reuses extracted image metadata. Filesystem and binary sources, generated IIIF
responses, and HTTP calls made by extension code itself are outside this cache. See
[HTTP source cache configuration](configuration.md#http-source-cache) for settings and defaults.

When Wolpi needs to inspect an eligible source for `info.json`, it downloads the complete image
first. Subsequent requests can reuse the file and metadata. If a resolver supplies `imageInfo`,
Wolpi does not download the source just to warm the cache for `info.json`.

### Storage and reuse rules

Only complete `200` responses with a valid, positive `Content-Length` qualify for storage. The
image must fit both the per-resource and total body-byte limits. Unknown-length and partial
responses stream without caching; Wolpi does not send range probes. Failed or incomplete downloads
do not create entries. A body that ends before its declared length produces HTTP `502`.

Origin responses with `no-store`, `private`, `Set-Cookie`, `Vary: *`, or variation on conditional
validator headers bypass storage. Requests carrying `Authorization` or `Cookie` need explicit
origin permission through `public`, `s-maxage`, or `must-revalidate`; those permissions do not
override other storage restrictions. Source-request `no-store`, `Range`, `If-Range`, `If-Match`,
and `If-Unmodified-Since` also bypass storage.

By default, Wolpi distinguishes cached sources by URL, source request headers, and resolver-provided
versions. Origin conditional validators do not identify separate copies. Extensions using rotating
signed URLs can supply [stable source identities](../extension-development.md#stable-cache-identities-for-http-sources).
Authorization and resolution still run for every client request, even when a fresh hit skips the
origin. Source-cache reuse does not change the client-facing `Cache-Control` policy.

HTTP sources require identity encoding, including when caching is disabled. A conflicting resolver
`Accept-Encoding` value produces HTTP `500`; an unsupported origin content encoding produces HTTP
`502` rather than a cache bypass.

### Freshness and origin failures

Wolpi reuses an image until the earlier of the configured `max-freshness` cap and the origin's
freshness deadline. Origin `s-maxage` takes precedence over `max-age`, then `Expires`. Wolpi accounts
for `Date`, `Age`, response delay, and elapsed time. Without explicit origin freshness, it uses the
configured cap. Hits do not extend freshness.

Expired files can remain on disk for conditional revalidation with ETag or Last-Modified. An origin
`304` refreshes metadata without downloading the body; a changed `200` replaces it. Without origin
validators, Wolpi must download the image again. Source-request `no-cache` or `max-age=0`, and
origin `no-cache`, require validation before reuse.

If contacting the origin fails, times out, or returns HTTP 5xx, Wolpi can reuse an expired image
within the configured `stale-if-error` window. Set that allowance to `0s` to disable fallback.
Fallback neither extends freshness nor prevents eviction; later independent requests still try
the origin.

Fallback does not override source-request `no-cache` or `max-age=0`, or origin `no-cache`,
`must-revalidate`, `proxy-revalidate`, or `s-maxage`. Wolpi checks storage permission against the
current source request headers. HTTP 4xx, unsupported encodings, and failures after a replacement
`200` download begins still fail the request.

The `http-timeouts.sources.request` limit covers waiting for another acquisition, retries, and the
complete cached-body download, including stalled transfers on Java 25. On timeout, Wolpi stops the
download and removes incomplete files. It returns HTTP `504` unless an eligible stale image is
available. Direct streams have [more limited timeout protection](configuration.md#http-timeouts).

### Response diagnostics

The optional `X-Wolpi-Http-Source-Cache` header describes the initial source acquisition:

| Value | Meaning |
| --- | --- |
| `disk-hit` | Reused the cached file without contacting the origin. |
| `miss` | Downloaded and cached the complete source. |
| `revalidated` | Reused the body after an origin `304`. |
| `stale` | Reused an expired body after an origin failure or acquisition timeout. |
| `bypass=<reason>` | Did not retain the acquisition for future reuse. |

Bypass reasons:

- `disabled`: caching is disabled.
- `unknown-length`, `empty-body`, `resource-too-large`: the declared size is ineligible.
- `cache-too-small`, `capacity`: the body cannot fit the budget, or active requests have reserved
  or pinned the available space.
- `no-store`, `private`, `vary-star`, `vary-validator`, `set-cookie`, `authenticated`: origin policy
  prevents storage.
- `request-no-store`, `range-request`, `precondition`: source request headers require bypass.
- `partial-response`, `upstream-status`: the origin did not return an eligible complete `200`.
- `storage-error`: reserving disk space or refreshing stored metadata failed.

After a `304` that prohibits storage, Wolpi can finish the current request from its pinned file
but remove it from the cache for future requests; diagnostics then report `bypass=<reason>`.
Reopening the same body within a request does not turn `miss` into a hit. Requests that need no
HTTP source access omit the header. With CORS enabled, Wolpi exposes it to browser clients. Set
`http-source-cache.expose-response-headers: false` to suppress it without disabling caching.

### Local storage

Wolpi requires a dedicated local directory per instance and refuses to start if another process
owns it. It retains complete entries across restarts and checks their metadata and body lengths
on recovery, discarding invalid entries and orphaned cache files. The in-memory index holds at
most 10,000 entries. Normal file reads can come from the kernel page cache without keeping a
second copy of complete source images in the Java heap.

Active requests keep their selected files until decoding finishes, including after replacement
or invalidation. Pending downloads and old files still in use count against the body-byte budget.
If Wolpi cannot free enough space, it streams without caching rather than exceed that budget.
Storage failures after consuming a download can fail the request. Metadata and filesystem
overhead require space beyond the body-byte limit.

Do not change cache files while Wolpi runs: it checks bodies on publication and recovery, not on
every lookup. Protect the directory like the source images, since cached files can contain private
content. During shutdown, the old instance retains directory ownership until accepted acquisitions
and file readers finish. For multi-instance deployments, see
[routing by image identifier](../how-to/expose-wolpi-behind-a-reverse-proxy.md#route-requests-by-image-identifier).

## Related reference

- [Configuration](./configuration.md) for `http`, `iiif`, cache-control, and source-cache settings
- [Observability](./observability.md) for health endpoints, metrics, and logging
