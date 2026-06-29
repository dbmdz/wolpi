package dev.mdz.wolpi.image;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VipsError;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsBandFormat;
import app.photofox.vipsffm.enums.VipsInterpretation;
import app.photofox.vipsffm.enums.VipsOperationRelational;
import app.photofox.vipsffm.enums.VipsPrecision;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/// Provides static methods for performing different binarization algorithms on an input [VImage]
public class Binarization {
    /// PNG bytes of a static mask used to perform blue noise dithering
    private static final byte[] ditheringMask;

    static {
        try {
            ditheringMask = new ClassPathResource("assets/blue_noise_128.png").getContentAsByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /// Binarize an image using Otsu's method, resulting in an image that keeps clearly defined lines
    /// and shapes, but elimites texture and gradients.
    public static VImage otsu(VImage image) {
        // Blur the image a bit before to help with noise, sigma=0.85 corresponds to a ~5x5 kernel
        image = image.gaussblur(0.85, VipsOption.Enum("precision", VipsPrecision.PRECISION_INTEGER));
        double binarizationThreshold = otsuThreshold(image);
        return image.relationalConst(
                VipsOperationRelational.OPERATION_RELATIONAL_MOREEQ, List.of(binarizationThreshold));
    }

    /// Binarize an image using a blue noise dithering method, resulting in an image that keeps
    /// gradients and texture, but eliminates clearly defined lines and shapes.
    public static VImage blueNoiseDither(Arena arena, VImage image) {
        return blueNoiseBinarize(arena, image, 128);
    }

    /// Determine a good binarization threshold based on Otsu's algorithm by maximizing the variance
    /// between the pixel values below and above the threshold.
    ///
    /// There's an animation in [the Wikipedia article](https://en.wikipedia.org/wiki/Otsu's_method)
    /// that illustrates the approach very well.
    private static int otsuThreshold(VImage image) throws VipsError {
        VImage gray = image.colourspace(VipsInterpretation.INTERPRETATION_B_W).cast(VipsBandFormat.FORMAT_UCHAR);

        // Generate a histogram of all pixels in the image with vips, copy it to a Java array
        VImage hist = gray.histFind();
        MemorySegment histMem = hist.writeToMemory();
        long[] counts = new long[256];
        long total = 0;
        long totalSum = 0;
        for (int i = 0; i < 256; i++) {
            long c = Integer.toUnsignedLong(histMem.get(ValueLayout.JAVA_INT, (long) i * Integer.BYTES));
            counts[i] = c;
            total += c;
            totalSum += c * i;
        }

        // Image is all-black, threshold is irrelevant
        if (total == 0) {
            return 0;
        }

        // Determine the lowest pixel value
        int min = 0;
        while (min < 256 && counts[min] == 0) {
            min++;
        }

        // Determine the largest pixel value
        int max = 255;
        while (max >= 0 && counts[max] == 0) {
            max--;
        }

        // Image has a uniform value, threshold is that value
        if (min >= max) {
            return min;
        }

        // Loop over all possible thresholds and find the one that maximizes the variance between
        // the two pixel clusters determined by the threshold.
        long belowCount = 0;
        long belowSum = 0;
        int bestThreshold = min + 1;
        double bestScore = -1.0;
        for (int threshold = min + 1; threshold < max; threshold++) {
            int moved = threshold - 1;
            long c = counts[moved];

            belowCount += c;
            belowSum += c * moved;

            long aboveCount = total - belowCount;
            if (belowCount == 0 || aboveCount == 0) {
                continue;
            }

            // Determine inter-class variance
            double numerator = (double) total * belowSum - (double) belowCount * totalSum;
            double score = (numerator * numerator) / ((double) belowCount * aboveCount);

            if (score > bestScore) {
                bestScore = score;
                bestThreshold = threshold;
            }
        }

        return bestThreshold;
    }

    public static VImage blueNoiseBinarize(Arena arena, VImage image, int maskSize) {
        VImage mask = VImage.newFromBytes(arena, ditheringMask);
        VImage gray = image.colourspace(VipsInterpretation.INTERPRETATION_B_W).cast(VipsBandFormat.FORMAT_UCHAR);
        int across = (int) Math.ceil((double) gray.getWidth() / maskSize);
        int down = (int) Math.ceil((double) gray.getHeight() / maskSize);
        VImage tiled = mask.replicate(across, down);
        tiled = tiled.extractArea(0, 0, gray.getWidth(), gray.getHeight());
        return gray.relational(tiled, VipsOperationRelational.OPERATION_RELATIONAL_MORE);
    }
}
