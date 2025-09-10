package dev.mdz.wolpi.model;

import java.util.List;

public record ImageInfo(
    int nativeWidth, int nativeHeight, List<ImageSize> sizes, List<TileSize> tileSizes) {

  public ImageSize nativeSize() {
    return new ImageSize(nativeWidth, nativeHeight);
  }
}
