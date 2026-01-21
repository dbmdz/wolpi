package dev.mdz.wolpi.metrics;

import dev.mdz.wolpi.iiif.model.CropRectangle;
import dev.mdz.wolpi.model.ImageSize;

/// T-shirt size buckets for image dimensions and areas, used for metrics.
public enum SizeBucket {
    TINY,
    SMALL,
    MEDIUM,
    LARGE,
    XLARGE,
    HUGE;

    /// Bucket an image size by its largest dimension
    public static SizeBucket fromDimension(ImageSize size) {
        int maxDim = Math.max(size.width(), size.height());
        if (maxDim <= 256) return TINY;
        if (maxDim <= 512) return SMALL;
        if (maxDim <= 1024) return MEDIUM;
        if (maxDim <= 2048) return LARGE;
        if (maxDim <= 4096) return XLARGE;
        return HUGE;
    }

    /// Bucket a crop rectangle by its area
    public static SizeBucket fromArea(CropRectangle crop) {
        int area = crop.width() * crop.height();
        if (area <= 65536) return TINY; // 256x256
        if (area <= 262144) return SMALL; // 512x512
        if (area <= 1048576) return MEDIUM; // 1024x1024
        if (area <= 4194304) return LARGE; // 2048x2048
        if (area <= 16777216) return XLARGE; // 4096x4096
        return HUGE;
    }

    /// Convert to lowercase string for metric tags
    public String toTag() {
        return name().toLowerCase();
    }
}
