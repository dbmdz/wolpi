package dev.mdz.wolpi.image;

import static dev.mdz.wolpi.testutil.WolpiAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsHelper;
import dev.mdz.wolpi.config.IIIFConfig;
import dev.mdz.wolpi.config.IIIFConfig.Qualities;
import dev.mdz.wolpi.config.IIIFConfig.ScalingFeature;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.ExtensionRuntime;
import dev.mdz.wolpi.iiif.ImageRequestParser;
import dev.mdz.wolpi.iiif.exceptions.NotImplementedException;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.iiif.model.ImageRequest;
import dev.mdz.wolpi.metrics.WolpiMetrics;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.ImageSource;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

public class ImageProcessorTest {
    private static Arena arena;
    private static ImageProcessor processor;
    private static ImageLoader loader;

    @BeforeAll
    public static void setup() {
        Vips.init();
        var iiifConfig = mock(IIIFConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(iiifConfig.features().scaling()).thenReturn(new ScalingFeature(true, true, true, true, true, false));
        when(iiifConfig.qualities()).thenReturn(new Qualities("color", List.of("color")));
        when(iiifConfig.formats().allowed().contains(any())).thenReturn(true);
        var config = new WolpiConfig(
                Path.of("/tmp/wolpi-test-tmp"),
                Path.of("src/test/resources/images"),
                null,
                null,
                iiifConfig,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
        arena = Arena.ofConfined();
        var runtime = mock(ExtensionRuntime.class);
        when(runtime.hasExtensionsForHook(any())).thenReturn(false);
        var metrics = mock(WolpiMetrics.class);
        var timer = mock(WolpiMetrics.ImageProcessingTimer.class);
        when(metrics.startImageProcessingTimer(any(), any(), any(), any(), any()))
                .thenReturn(timer);
        loader = new ImageLoader(config, arena, null, runtime, null, metrics);
        processor = new ImageProcessor(arena, config, loader, new ImageRequestParser(config), runtime, metrics);
    }

    @AfterAll
    public static void teardown() {
        arena.close();
    }

    @Test
    @DisplayName("should convert embedded colorspace to sRGB")
    public void testEmbeddedColorspace() throws NotImplementedException, IOException, InterruptedException {
        var ident = "embedded_colorspace.jp2";
        var request = ImageRequest.full(ident, IIIFVersion.V3);
        var resolved = new FilesystemResolvedImage(Path.of("src/test/resources/images/embedded_icc_colorspace.jp2"));
        var source = new ImageSource(ident, resolved, null, null);
        var info = loader.getImageInfo(source);
        assertThat(info).isNotNull();
        VImage processed = processor.processImage(source, request);
        var encoded = processor.encodeImage(processed, info, ImageRequest.full(ident, IIIFVersion.V3));
        var bytes = new byte[encoded.data().remaining()];
        encoded.data().get(bytes);
        VImage finalImage = VImage.newFromBytes(arena, bytes);
        boolean hasICC =
                VipsHelper.image_get_typeof(arena, finalImage.getUnsafeStructAddress(), "icc-profile-data") != 0;
        assertThat(hasICC).isFalse();
    }
}
