package dev.mdz.iiif.wolpi.model.params;

/// Rectangular section cropped from an image, expressed in non-fractional pixels.
public record CropRectangle(int x, int y, int width, int height) {}
