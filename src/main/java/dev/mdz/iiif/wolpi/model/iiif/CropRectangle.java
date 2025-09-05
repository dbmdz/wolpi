package dev.mdz.iiif.wolpi.model.iiif;

/// Rectangular section cropped from an image, expressed in non-fractional pixels.
public record CropRectangle(int x, int y, int width, int height) {}
