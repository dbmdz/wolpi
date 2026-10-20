package dev.mdz.wolpi.image;

import static org.assertj.core.api.Assertions.assertThat;

import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsForeignTiffCompression;
import app.photofox.vipsffm.enums.VipsForeignWebpPreset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ImageProcessorEncodingOptionsTest {
    @Test
    void resolvesConfiguredEnumNamesFromDiscoveredVipsClasses() {
        var compression = VipsForeignTiffCompression.FOREIGN_TIFF_COMPRESSION_LZW;
        var preset = VipsForeignWebpPreset.FOREIGN_WEBP_PRESET_PHOTO;
        Map<String, List<VipsOption>> options = ReflectionTestUtils.invokeMethod(
                ImageProcessor.class,
                "determineEncodingOptions",
                Map.of(
                        "tif", Map.of("compression", compression.getName()),
                        "webp", Map.of("preset", preset.getName())));

        assertThat(options.get("tif")).singleElement().satisfies(option -> {
            assertThat(option.key()).isEqualTo("compression");
            assertThat(option).isInstanceOf(VipsOption.Enum.class);
            assertThat(((VipsOption.Enum) option).valueOrNull()).isSameAs(compression);
        });
        assertThat(options.get("webp")).singleElement().satisfies(option -> {
            assertThat(option.key()).isEqualTo("preset");
            assertThat(option).isInstanceOf(VipsOption.Enum.class);
            assertThat(((VipsOption.Enum) option).valueOrNull()).isSameAs(preset);
        });
    }

    @Test
    void preservesStringsThatAreNotEnumNames() {
        Map<String, List<VipsOption>> options = ReflectionTestUtils.invokeMethod(
                ImageProcessor.class, "determineEncodingOptions", Map.of("jpg", Map.of("profile", "srgb")));

        assertThat(options.get("jpg")).singleElement().satisfies(option -> {
            assertThat(option.key()).isEqualTo("profile");
            assertThat(option).isInstanceOf(VipsOption.String.class);
            assertThat(((VipsOption.String) option).valueOrNull()).isEqualTo("srgb");
        });
    }
}
