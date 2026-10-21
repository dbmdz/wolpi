package dev.mdz.wolpi.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class ImageSource {

    private final String identifier;
    private final ResolvedImage resolvedImage;

    /// Resolver-supplied validators, fixed for this source and used to partition HTTP cache keys.
    private final @Nullable CacheInfo resolverValidators;

    /// Validators learned from filesystem metadata or the HTTP body actually acquired.
    private @Nullable CacheInfo sourceValidators;

    private @Nullable ImageInfo imageInfo;

    private @Nullable HttpSourceLease httpSourceLease;
    private @Nullable String httpSourceCacheStatus;

    /// @param identifier Identifier of the image
    /// @param resolvedImage What the identifier resolved to, used to fetch the image data
    /// @param imageInfo Optional image information, such as dimensions, available image sizes, etc
    /// @param cacheInfo Optional cache information from the resolver
    public ImageSource(
            String identifier,
            ResolvedImage resolvedImage,
            @Nullable ImageInfo imageInfo,
            @Nullable CacheInfo cacheInfo) {
        this.identifier = identifier;
        this.resolvedImage = resolvedImage;
        this.imageInfo = imageInfo;
        this.resolverValidators = cacheInfo;
    }

    /// Public identifier of the image this source is for
    public String identifier() {
        return identifier;
    }

    /// The actual resolved image for this source
    public ResolvedImage resolvedImage() {
        return resolvedImage;
    }

    /// Technical metadata for the image, either from the resolver or directly from the actual image
    public @Nullable ImageInfo imageInfo() {
        return imageInfo;
    }

    public void setImageInfo(ImageInfo info) {
        this.imageInfo = info;
    }

    /// Cache information that can be forwarded as HTTP validators to clients
    ///
    /// A resolver-supplied value takes precedence and Wolpi does not replace it with upstream
    /// validators. Otherwise, the value comes from filesystem metadata or from upstream HTTP
    /// response headers.
    public @Nullable CacheInfo cacheInfo() {
        return resolverValidators != null ? resolverValidators : sourceValidators;
    }

    /// Record validators learned from the acquired source; null clears the previous source values.
    /// Resolver validators still take precedence in cacheInfo().
    public void updateSourceValidators(@Nullable CacheInfo validators) {
        this.sourceValidators = validators;
    }

    /// Active HTTP body lease, backed by either a pinned file or a one-shot origin stream.
    public @Nullable HttpSourceLease httpSourceLease() {
        return httpSourceLease;
    }

    /// Attach the active HTTP body lease for this source.
    public void setHttpSourceLease(HttpSourceLease lease) {
        this.httpSourceLease = lease;
        // Keep the initial acquisition outcome even if this request reopens the body later.
        if (httpSourceCacheStatus == null) {
            httpSourceCacheStatus = lease.cacheStatusHeaderValue();
        }
    }

    /// Initial source cache acquisition result, formatted for the diagnostic response header.
    public @Nullable String httpSourceCacheStatus() {
        return httpSourceCacheStatus;
    }

    /// Resolver-supplied validators, used to partition cached HTTP bodies.
    ///
    /// Fixed for the lifetime of the source, so reopening a body within one request keeps the same
    /// cache key. Null when the resolver supplied none; never holds validators learned from
    /// upstream, which would create a new cache entry for every new origin tag.
    public @Nullable CacheInfo resolverValidators() {
        return resolverValidators;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != this.getClass()) {
            return false;
        }
        var that = (ImageSource) obj;
        return Objects.equals(this.identifier, that.identifier)
                && Objects.equals(this.resolvedImage, that.resolvedImage)
                && Objects.equals(this.imageInfo, that.imageInfo)
                && Objects.equals(this.cacheInfo(), that.cacheInfo());
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, resolvedImage, imageInfo, cacheInfo());
    }

    @Override
    public String toString() {
        return "ImageSource[" + "identifier="
                + identifier + ", " + "resolvedImage="
                + resolvedImage + ", " + "imageInfo="
                + imageInfo + ", " + "cacheInfo="
                + cacheInfo() + ']';
    }
}
