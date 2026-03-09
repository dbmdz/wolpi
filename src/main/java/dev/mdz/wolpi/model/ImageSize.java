package dev.mdz.wolpi.model;

public record ImageSize(int width, int height) {
    public double aspectRatio() {
        return (double) width / height;
    }

    public ImageSize scale(double factor) {
        return new ImageSize((int) Math.round(width * factor), (int) Math.round(height * factor));
    }
}
