package dev.mdz.iiif.wolpi.model.image;

public record ImageSize(int width, int height) {
  public double aspectRatio() {
    return (double) width / height;
  }
}
