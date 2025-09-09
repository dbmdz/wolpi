package dev.mdz.wolpi.model;

import java.util.List;

public record ImageInfo(
    int nativeWidth, int nativeHeight, List<ImageSize> sizes, List<TileSize> getTileSizes) {

  public ImageSize nativeSize() {
    return new ImageSize(nativeWidth, nativeHeight);
  }
}
