package dev.mdz.wolpi.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record ImageInfo(
        @Nullable String format, ImageSize nativeSize, List<ImageSize> sizes, List<TileSize> tileSizes) {
    public ImageInfo(ImageSize nativeSize, List<ImageSize> sizes, List<TileSize> tileSizes) {
        this(null, nativeSize, sizes, tileSizes);
    }
}
