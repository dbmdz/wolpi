package dev.mdz.wolpi.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class ImageSource {

    private final String identifier;
    private final ResolvedImage resolvedImage;
    private @Nullable CacheInfo cacheInfo;

    private @Nullable ImageInfo imageInfo;

    /// @param identifier Identifier of the image
    /// @param resolvedImage What the identifier resolved to, used to fetch the image data
    /// @param imageInfo Optional image information, such as dimensions, available image sizes, etc
    /// @param cacheInfo Optional cache information, such as ETag and Last-Modified
    public ImageSource(
            String identifier,
            ResolvedImage resolvedImage,
            @Nullable ImageInfo imageInfo,
            @Nullable CacheInfo cacheInfo) {
        this.identifier = identifier;
        this.resolvedImage = resolvedImage;
        this.imageInfo = imageInfo;
        this.cacheInfo = cacheInfo;
    }

    public String identifier() {
        return identifier;
    }

    public ResolvedImage resolvedImage() {
        return resolvedImage;
    }

    public @Nullable ImageInfo imageInfo() {
        return imageInfo;
    }

    public void setImageInfo(ImageInfo info) {
        this.imageInfo = info;
    }

    public @Nullable CacheInfo cacheInfo() {
        return cacheInfo;
    }

    public void setCacheInfo(CacheInfo cacheInfo) {
        this.cacheInfo = cacheInfo;
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
                && Objects.equals(this.cacheInfo, that.cacheInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier, resolvedImage, imageInfo, cacheInfo);
    }

    @Override
    public String toString() {
        return "ImageSource[" + "identifier="
                + identifier + ", " + "resolvedImage="
                + resolvedImage + ", " + "imageInfo="
                + imageInfo + ", " + "cacheInfo="
                + cacheInfo + ']';
    }
}
