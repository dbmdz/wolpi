package dev.mdz.iiif.wolpi.model.image;

import dev.mdz.iiif.wolpi.model.params.IIIFVersion;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record ImageInfo(
    int nativeWidth,
    int nativeHeight,
    List<ImageSize> sizes,
    List<TileSize> getTileSizes,
    @Nullable String mime) {

  public Map<String, Object> toIIIF(IIIFVersion version) {
    return Map.of();
  }

  public ImageSize nativeSize() {
    return new ImageSize(nativeWidth, nativeHeight);
  }
}
