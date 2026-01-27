package dev.mdz.wolpi.iiif.model;

/// Rectangular section cropped from an image, expressed in non-fractional pixels.
public record CropRectangle(int x, int y, int width, int height) {
    public double aspectRatio() {
        return (double) width / (double) height;
    }
}
