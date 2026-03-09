package dev.mdz.wolpi.iiif.model;

import dev.mdz.wolpi.model.ImageSize;

/// Rectangular section cropped from an image, expressed in non-fractional pixels.
public record CropRectangle(int x, int y, int width, int height) {
    public double aspectRatio() {
        return (double) width / (double) height;
    }

    public ImageSize size() {
        return new ImageSize(width, height);
    }
}
