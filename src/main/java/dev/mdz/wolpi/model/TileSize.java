package dev.mdz.wolpi.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record TileSize(int width, @Nullable Integer height, List<Integer> scaleFactors) {}
