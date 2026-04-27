package dev.mdz.wolpi.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mdz.wolpi.iiif.model.CropRectangle;
import dev.mdz.wolpi.model.ImageSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SizeBucket")
class SizeBucketTest {

    @Test
    @DisplayName("should return unknown for null image size")
    void shouldReturnUnknownForNullImageSize() {
        assertThat(SizeBucket.fromDimension(null)).isEqualTo(SizeBucket.UNKNOWN);
    }

    @Test
    @DisplayName("should return unknown for null crop area")
    void shouldReturnUnknownForNullCropArea() {
        assertThat(SizeBucket.fromArea(null)).isEqualTo(SizeBucket.UNKNOWN);
    }

    @Test
    @DisplayName("should bucket known crop areas")
    void shouldBucketKnownCropAreas() {
        assertThat(SizeBucket.fromArea(new CropRectangle(0, 0, 300, 300))).isEqualTo(SizeBucket.SMALL);
        assertThat(SizeBucket.fromDimension(new ImageSize(900, 700))).isEqualTo(SizeBucket.MEDIUM);
    }
}
