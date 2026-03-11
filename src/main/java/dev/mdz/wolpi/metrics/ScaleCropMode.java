package dev.mdz.wolpi.metrics;

/// The mode to use when both scaling and cropping an image.
public enum ScaleCropMode {
    /// Fastest method, only downscaling, using shrink-on-load if possible
    SCALE_NO_CROP,
    /// Faster for untiled image formats, only possible if no custom processing
    /// is performed
    SCALE_THEN_CROP,
    /// Default method, works for all cases, but slower for image formats without tiling
    CROP_THEN_SCALE
}
