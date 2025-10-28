package dev.mdz.wolpi.testutil;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsExtend;
import app.photofox.vipsffm.enums.VipsInterpretation;
import java.awt.Color;
import java.lang.foreign.Arena;
import java.util.List;

public class VImageHelpers {
    /// Create an empty image of the specified dimensions and color.
    ///
    /// @param arena The memory arena to use for allocations.
    /// @param width The width of the image.
    /// @param height The height of the image.
    /// @param color The color to fill the image with.
    /// @return The created empty image (3 bands, UCHAR, sRGB).
    public static VImage createEmptyImage(Arena arena, int width, int height, Color color) {
        double red = color.getRed();
        double green = color.getGreen();
        double blue = color.getBlue();
        return VImage
                // Start with a black 1x1 image with 3 bands (RGB)
                .black(arena, 1, 1, VipsOption.Int("bands", 3))
                // Scale the pixel value to the desired color (px = 1.0 * px + color)
                .linear(List.of(1.0), List.of(red, green, blue))
                // Convert to sRGB format
                .copy(VipsOption.Enum("interpretation", VipsInterpretation.INTERPRETATION_sRGB))
                // Resize to the desired dimensions by copying the pixel
                .embed(0, 0, width, height, VipsOption.Enum("extend", VipsExtend.EXTEND_COPY));
    }
}
