package dev.mdz.wolpi.testutil;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.enums.VipsOperationRelational;
import java.nio.file.Path;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;

/// Custom assertions for Wolpi tests.
public class WolpiAssertions extends Assertions {

    /// Entry point for VImage assertions.
    public static VImageAssert assertThat(VImage vImage) {
        return new VImageAssert(vImage);
    }

    public static class VImageAssert extends AbstractAssert<VImageAssert, VImage> {

        public VImageAssert(VImage actual) {
            super(actual, VImageAssert.class);
        }

        /// Asserts that the image has the expected dimensions.
        public VImageAssert hasDimensions(int expectedWidth, int expectedHeight) {
            isNotNull();

            int actualWidth = actual.getWidth();
            int actualHeight = actual.getHeight();

            if (actualWidth != expectedWidth || actualHeight != expectedHeight) {
                failWithMessage(
                        "Expected image dimensions to be <%dx%d> but was <%dx%d>",
                        expectedWidth, expectedHeight, actualWidth, actualHeight);
            }

            return this;
        }

        /// Asserts that the image is pixel-identical to the expected image.
        public VImageAssert equals(VImage expectedImage) {
            isNotNull();

            var min = expectedImage
                    .relational(actual, VipsOperationRelational.OPERATION_RELATIONAL_EQUAL)
                    .min();
            if (min != 255) {
                failWithMessage(
                        "Expected images to be equal, but minimum difference was <%.4f>, i.e. not all pixels were equal",
                        min);
            }

            return this;
        }

        /// Asserts that a region of the image is pixel-identical to the expected image.
        public VImageAssert regionEquals(int x, int y, VImage expectedImage) {
            isNotNull();

            var region = actual.extractArea(x, y, expectedImage.getWidth(), expectedImage.getHeight());
            region.writeToFile("/tmp/region-%d-%d.png".formatted(x, y));
            expectedImage.writeToFile("/tmp/expected-%d-%d.png".formatted(x, y));
            var min = expectedImage
                    .relational(region, VipsOperationRelational.OPERATION_RELATIONAL_EQUAL)
                    .min();
            if (min != 255) {
                failWithMessage(
                        "Expected image region to be equal to expected image, but minimum difference was <%.4f>, i.e. not all pixels were equal",
                        min);
            }

            return this;
        }

        /// Writes the actual image to the specified path for debugging purposes.
        ///
        /// Can be very helpful when a test fails and you want to inspect the images.
        public VImageAssert withDebugImage(Path outputPath) {
            isNotNull();
            try {
                actual.writeToFile(outputPath.toString());
            } catch (Exception e) {
                failWithMessage("Failed to write debug image to path <%s>: %s", outputPath, e.getMessage());
            }
            return this;
        }
    }
}
