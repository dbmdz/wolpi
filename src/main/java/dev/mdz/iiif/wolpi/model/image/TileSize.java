package dev.mdz.iiif.wolpi.model.image;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record TileSize(int width, @Nullable Integer height, List<Integer> scaleFactors) {}
