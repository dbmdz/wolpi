package dev.mdz.wolpi.model;

import java.util.List;

public record ImageInfo(ImageSize nativeSize, List<ImageSize> sizes, List<TileSize> tileSizes) {}
