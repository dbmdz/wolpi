# ADR 0001: Cache complete HTTP image sources on instance-local disk

## Status

Accepted.

## Context

Community feedback highlighted the cost and latency of repeatedly fetching HTTP
image sources, particularly for extensions serving images from S3. A tiling
viewer requests many different tiles from one image, often concurrently.
Previously, Wolpi retrieved the same source independently for each tile request.

From our experiences with client-facing derivative caches, we observed a low
hit ratio and little benefit. Caching the source instead lets different tile
requests reuse one download. This targets origin traffic and source-fetch
latency; it does not eliminate image processing or client-facing transfer
costs. Transfer-cost savings depend on the storage provider and deployment
topology.

## Decision

Cache complete, eligible HTTP source images as immutable files on instance-local
disk. Coalesce concurrent acquisitions of the same source so requests can share
a successful download. Cache source bytes and extracted image metadata, while
keeping native images and processing resources request-local.

Start with whole-resource caching rather than range caching to limit complexity.
Apply a configurable maximum resource size and a hard source-body byte budget.
Retain usable entries across restarts.

Give each instance exclusive ownership of its cache directory. Avoid an
additional deployment service and shared-filesystem coordination. Recommend
identifier-based routing across instances to improve reuse, accepting duplicate
downloads and storage when requests reach different instances. Ordinary file
reads can benefit from the kernel page cache without a separate
application-managed memory cache.

Revalidate stored sources after a bounded freshness period. Propagate origin
failures rather than serving stale content.

## Guarantees

- Run extension authorization and identifier resolution for every client
  request, including cache hits.
- Keep source identities isolated and respect HTTP restrictions on storage and
  reuse. A matching identity alone does not grant permission to reuse content.
- Publish only complete downloads. Protect the selected bytes for as long as
  active requests may read them, including during lazy native decoding.
- Count pending downloads and old bodies still in use against the body-byte
  budget. Bypass caching rather than exceed that budget. Metadata and
  filesystem overhead require additional space.

## Consequences

### Extension API and authorization

A fresh cache hit can skip the origin entirely. Extensions must enforce client
access themselves rather than rely on the origin checking credentials on every
request. If origin validation is essential for each request, extensions must
require revalidation or bypass caching. Resolver-supplied validators partition
cached source versions.

### Breaking change: client and origin validators

Implementing source reuse required separating IIIF client conditions from origin
revalidation. Client validators no longer become origin request conditions.
Wolpi evaluates client conditions separately and uses stored origin validators
to revalidate source bytes. Resolver-supplied client validators remain
authoritative; they need not match origin validators.

Without a cached body, Wolpi fetches the source without forwarding client
validators, including when caching is disabled. Extensions must not depend on
forwarding client conditions to obtain an upstream `304` in place of source
bytes. Source-cache freshness is independent of client-facing IIIF cache policy.

### Performance and operation

A cold eligible request downloads the complete source before image inspection
or decoding, even for a small tile. Oversized, unknown-length, or otherwise
ineligible sources continue through the streaming path and may incur repeated
origin transfers. We accept this limitation for multi-gigabyte images rather
than introduce range caching in this decision.

Disk pressure can cause bypasses and increase origin traffic. Each instance
needs a dedicated local directory, protected like the source images; shared
directories and network filesystems are unsupported. Persistence enables reuse
after restarts, but the cache is disposable storage, not a durable source of
record.

## Alternatives considered

- **Client-facing derivative caching alone:** our existing cache showed poor
  reuse for the motivating workload. Source caching shares downloads across
  different derivatives; derivative caching remains a possible complement.
- **Range/block caching:** could reduce cold transfers for large images, but
  adds seekable access, cross-range consistency, and block coordination. Deferred
  because it is more complex than whole-resource caching.
- **An additional cache service, such as Redis:** rejected to avoid deployment
  dependencies, cost, and operational complexity.
- **A shared network-filesystem cache:** rejected to avoid coordinating writes
  and reader lifetimes across processes. Independent local caches with
  identifier-based routing provide a simpler deployment model.

See the [configuration reference](../reference/configuration.md#http-source-cache)
for settings, defaults, and operational details.
