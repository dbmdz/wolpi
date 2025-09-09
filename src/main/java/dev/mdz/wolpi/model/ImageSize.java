package dev.mdz.wolpi.model;

public record ImageSize(int width, int height) {
  public double aspectRatio() {
    return (double) width / height;
  }
}
