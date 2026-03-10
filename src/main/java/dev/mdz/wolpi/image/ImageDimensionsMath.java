package dev.mdz.wolpi.image;

import dev.mdz.wolpi.iiif.model.CropRectangle;
import dev.mdz.wolpi.model.ImageSize;

public class ImageDimensionsMath {

    /// Remaps a crop rectangle from native image coordinates into the coordinates of a pre-shrunk
    /// load result.
    ///
    /// @param crop the crop rectangle to remap, in coordinates of the original source image
    /// @param sourceSize the size of the original source image
    /// @param levelSize the size of the pre-shrunk load result to remap into
    /// @return a crop rectangle in the coordinate space of the pre-shrunk load result, that corresponds to the same
    ///         region of the original source image as the input crop rectangle
    public static CropRectangle remapCrop(CropRectangle crop, ImageSize sourceSize, ImageSize levelSize) {
        double scaleX = levelSize.width() / (double) sourceSize.width();
        double scaleY = levelSize.height() / (double) sourceSize.height();

        // X and Y are clamped to [0, level{Width/Height} -1]
        int x = Math.max(0, Math.min((int) Math.floor(crop.x() * scaleX), levelSize.width() - 1));
        int y = Math.max(0, Math.min((int) Math.floor(crop.y() * scaleY), levelSize.height() - 1));

        // Width and height are clamped to [1, levelWidth/Height - x/y]
        int width = Math.max(1, Math.min((int) Math.ceil(crop.width() * scaleX), levelSize.width() - x));
        int height = Math.max(1, Math.min((int) Math.ceil(crop.height() * scaleY), levelSize.height() - y));

        return new CropRectangle(x, y, width, height);
    }

    /// Computes the overall scaling factor from original image size to target size
    ///
    /// @return scaling factor, i.e. how many times the original image is reduced in size to get to the target size
    ///         (`> 1` → upscaling, `< 1` → downscaling)
    public static double getScalingFactor(ImageSize originalSize, ImageSize targetSize) {
        return Math.min(
                targetSize.width() / (double) originalSize.width(),
                targetSize.height() / (double) originalSize.height());
    }
}
