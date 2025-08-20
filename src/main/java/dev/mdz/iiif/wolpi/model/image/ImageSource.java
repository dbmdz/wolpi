package dev.mdz.iiif.wolpi.model.image;

import org.jspecify.annotations.Nullable;

/// @param identifier Identifier of the image
/// @param resolvedImage What the identifier resolved to, used to fetch the image data
/// @param imageInfo Optional image information, such as dimensions, available image sizes, etc
public record ImageSource(
    String identifier, ResolvedImage resolvedImage, @Nullable ImageInfo imageInfo) {}
