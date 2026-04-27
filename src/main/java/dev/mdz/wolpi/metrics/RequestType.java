package dev.mdz.wolpi.metrics;

import dev.mdz.wolpi.iiif.model.CropRectangle;
import dev.mdz.wolpi.model.ImageSize;
import org.jspecify.annotations.Nullable;

/// Classification of IIIF image requests for metrics purposes.
public enum RequestType {
    TILE,
    THUMBNAIL,
    FULL,
    OTHER;

    /// Classify a request based on crop and output characteristics
    public static RequestType classify(
            String cropSpec,
            @Nullable SizeBucket outputSize,
            @Nullable CropRectangle actualCrop,
            ImageSize sourceSize) {
        boolean isFullCrop = cropSpec.equals("full") || cropSpec.equals("square");

        // Tile: small crop from larger image, likely power-of-2 sized
        if (actualCrop != null && !isFullCrop) {
            boolean isSmallCrop =
                    actualCrop.width() < sourceSize.width() * 0.5 && actualCrop.height() < sourceSize.height() * 0.5;
            boolean isTileSized = (actualCrop.width() % 64 == 0)
                    || (actualCrop.height() % 64 == 0)
                    || (actualCrop.width() == actualCrop.height());
            if (isSmallCrop && isTileSized) {
                return TILE;
            }
        }

        // Thumbnail: small output, full crop
        if (isFullCrop && (outputSize == SizeBucket.TINY || outputSize == SizeBucket.SMALL)) {
            return THUMBNAIL;
        }

        // Full: large output, full crop
        if (isFullCrop
                && (outputSize == SizeBucket.MEDIUM
                        || outputSize == SizeBucket.LARGE
                        || outputSize == SizeBucket.XLARGE
                        || outputSize == SizeBucket.HUGE)) {
            return FULL;
        }

        return OTHER;
    }

    /// Convert to lowercase string for metric tags
    public String toTag() {
        return name().toLowerCase();
    }
}
