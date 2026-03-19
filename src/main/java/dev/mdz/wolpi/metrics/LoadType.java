package dev.mdz.wolpi.metrics;

public enum LoadType {
    /// No load-time scaling
    OPEN,
    /// Load-time scaling via vips_thumbnail API
    THUMBNAIL,
    /// Load-time scaling via direct loader params for pyramidal formats when the requested size
    /// matches one of the available reduced sizes in the image
    SHRINK_ON_LOAD,
}
