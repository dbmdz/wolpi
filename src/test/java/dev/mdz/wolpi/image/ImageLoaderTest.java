package dev.mdz.wolpi.image;

import static org.assertj.core.api.Assertions.assertThat;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import dev.mdz.wolpi.config.WolpiConfig;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ImageLoaderTest {
    private static Arena arena;
    private static ImageLoader imageLoader;

    @BeforeAll
    static void setup() {
        Vips.init();
        arena = Arena.ofConfined();
        imageLoader = new ImageLoader(
                new WolpiConfig(
                        Path.of("/tmp/wolpi-test-tmp"),
                        Path.of("src/test/resources/images"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of()),
                arena,
                null,
                null,
                null,
                null);
    }

    @AfterAll
    static void teardown() {
        arena.close();
    }

    @ParameterizedTest(name = "should map ''{0}'' to format ''{1}''")
    @CsvSource({"embedded_icc_colorspace.jpg, jpeg", "embedded_icc_colorspace.jp2, jp2k"})
    void testImageInfoFormatExtraction(String imageName, String expectedFormat) {
        VImage image = VImage.newFromFile(
                arena, Path.of("src/test/resources/images", imageName).toString());

        var info = imageLoader.getImageInfo(image);

        assertThat(info).isNotNull();
        assertThat(info.format()).isEqualTo(expectedFormat);
    }
}
