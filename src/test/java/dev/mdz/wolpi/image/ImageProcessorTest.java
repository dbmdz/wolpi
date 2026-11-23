package dev.mdz.wolpi.image;

import static dev.mdz.wolpi.testutil.WolpiAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.Vips;
import app.photofox.vipsffm.VipsHelper;
import app.photofox.vipsffm.enums.VipsInterpretation;
import dev.mdz.wolpi.config.IIIFConfig;
import dev.mdz.wolpi.config.IIIFConfig.Qualities;
import dev.mdz.wolpi.config.IIIFConfig.RegionFeature;
import dev.mdz.wolpi.config.IIIFConfig.ScalingFeature;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.config.WolpiConfig.BinarizationMethod;
import dev.mdz.wolpi.config.WolpiConfig.ExifOrientation;
import dev.mdz.wolpi.config.WolpiConfig.ImageProcessingConfig;
import dev.mdz.wolpi.extension.ExtensionRuntime;
import dev.mdz.wolpi.extension.model.ExtensionHooks;
import dev.mdz.wolpi.iiif.ImageRequestParser;
import dev.mdz.wolpi.iiif.exceptions.NotImplementedException;
import dev.mdz.wolpi.iiif.model.IIIFVersion;
import dev.mdz.wolpi.iiif.model.ImageRequest;
import dev.mdz.wolpi.metrics.RequestType;
import dev.mdz.wolpi.metrics.SizeBucket;
import dev.mdz.wolpi.metrics.WolpiMetrics;
import dev.mdz.wolpi.model.FilesystemResolvedImage;
import dev.mdz.wolpi.model.ImageSource;
import dev.mdz.wolpi.testutil.VImageHelpers;
import java.awt.Color;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

public class ImageProcessorTest {
    private static Arena arena;
    private ImageProcessor processor;
    private ImageLoader loader;
    private ExtensionRuntime runtime;
    private WolpiMetrics metrics;

    @BeforeAll
    public static void setup() {
        Vips.init();
        arena = Arena.ofConfined();
    }

    private IIIFConfig iiifConfig;

    @BeforeEach
    public void createProcessor() {
        createProcessor(new Qualities("color", List.of("color")), null);
    }

    private void createProcessor(Qualities qualities, @Nullable ImageProcessingConfig imageProcessing) {
        iiifConfig = mock(IIIFConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(iiifConfig.features().scaling()).thenReturn(new ScalingFeature(true, true, true, true, true, false));
        when(iiifConfig.features().region()).thenReturn(new RegionFeature(true, true, true));
        when(iiifConfig.qualities()).thenReturn(qualities);
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
                null,
                imageProcessing,
                Map.of(),
                null);
        runtime = mock(ExtensionRuntime.class);
        when(runtime.hasExtensionsForHook(any())).thenReturn(false);
        when(runtime.getSkippableHooks(any()))
                .thenReturn(EnumSet.of(
                        ExtensionHooks.PREPROCESS_IMAGE,
                        ExtensionHooks.CROP,
                        ExtensionHooks.SCALE,
                        ExtensionHooks.ROTATE,
                        ExtensionHooks.QUALITY,
                        ExtensionHooks.FORMAT));
        metrics = mock(WolpiMetrics.class);
        var timer = mock(WolpiMetrics.ImageProcessingTimer.class);
        when(metrics.startImageProcessingTimer(any(), any(), any(), any(), any()))
                .thenReturn(timer);
        loader = new ImageLoader(
                config, arena, runtime, null, metrics, mock(dev.mdz.wolpi.http.cache.HttpSourceCache.class));
        processor = new ImageProcessor(arena, config, loader, new ImageRequestParser(config), runtime, metrics);
    }

    private WolpiConfig config(Map<String, Map<String, Object>> encodingOptions) {
        return new WolpiConfig(
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
                null,
                null,
                encodingOptions,
                null);
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
        // The profile must be consumed by the colour transform, not just dropped when encoding,
        // otherwise removing it would silently shift colours instead of correcting them.
        var loaded = loader.loadImage(source);
        assertThat(hasField(loaded, "icc-profile-data"))
                .as("fixture carries an ICC profile")
                .isTrue();

        VImage processed = processor.processImage(source, request);
        assertThat(hasField(processed, "icc-profile-data"))
                .as("profile is consumed during processing")
                .isFalse();
        assertThat(VipsHelper.image_get_interpretation(processed.getUnsafeStructAddress()))
                .as("processed image is in sRGB")
                .isEqualTo(VipsInterpretation.INTERPRETATION_sRGB.getRawValue());

        var encoded = processor.encodeImage(processed, info, ImageRequest.full(ident, IIIFVersion.V3));
        var finalImage = VImage.newFromBytes(arena, asBytes(encoded.data()));
        assertThat(hasField(finalImage, "icc-profile-data"))
                .as("encoded image carries no profile")
                .isFalse();
    }

    @Test
    @DisplayName("should binarize JPEG sources with Otsu's method")
    public void shouldBinarizeJpegSourceWithOtsu() throws NotImplementedException, IOException, InterruptedException {
        // JPEG is decoded sequentially, so an implementation that evaluates the source twice (one pass
        // for the histogram, one for the thresholding) fails with "out of order read"
        createProcessor(
                new Qualities("color", List.of("color", "bitonal")),
                new ImageProcessingConfig(BinarizationMethod.OTSU, ExifOrientation.IGNORE));
        var ident = "embedded_icc_colorspace.jpg";
        var request = new ImageRequest(ident, IIIFVersion.V3, "full", "!300,300", "0", "bitonal", "png");
        var source = new ImageSource(
                ident,
                new FilesystemResolvedImage(Path.of("src/test/resources/images/embedded_icc_colorspace.jpg")),
                null,
                null);
        var info = loader.getImageInfo(source);

        var processed = processor.processImage(source, request);
        var encoded = processor.encodeImage(processed, info, request);

        var bytes = new byte[encoded.data().remaining()];
        encoded.data().get(bytes);
        var finalImage = VImage.newFromBytes(arena, bytes);
        Assertions.assertThat(VipsHelper.image_get_interpretation(finalImage.getUnsafeStructAddress()))
                .isEqualTo(VipsInterpretation.INTERPRETATION_B_W.getRawValue());
    }

    @Test
    @DisplayName("should encode image when crop extension handles invalid region")
    public void shouldEncodeImageWhenCropExtensionHandlesInvalidRegion()
            throws NotImplementedException, IOException, InterruptedException {
        var ident = "embedded_icc_colorspace.jpg";
        var request = new ImageRequest(ident, IIIFVersion.V3, "invalid", "max", "0", "default", "jpg");
        when(runtime.getSkippableHooks(request))
                .thenReturn(EnumSet.of(
                        ExtensionHooks.SCALE, ExtensionHooks.ROTATE, ExtensionHooks.QUALITY, ExtensionHooks.FORMAT));
        when(runtime.preCrop(any(), any(), any(), eq(request))).thenAnswer(invocation -> invocation.getArgument(0));

        var source = new ImageSource(
                ident,
                new FilesystemResolvedImage(Path.of("src/test/resources/images/embedded_icc_colorspace.jpg")),
                null,
                null);
        var info = loader.getImageInfo(source);

        VImage processed = processor.processImage(source, request);
        var encoded = processor.encodeImage(processed, info, request);

        ArgumentCaptor<SizeBucket> croppedArea = ArgumentCaptor.forClass(SizeBucket.class);
        ArgumentCaptor<RequestType> requestType = ArgumentCaptor.forClass(RequestType.class);
        verify(metrics).startImageProcessingTimer(any(), any(), croppedArea.capture(), requestType.capture(), any());

        Assertions.assertThat(encoded).isNotNull();
        Assertions.assertThat(croppedArea.getValue()).isEqualTo(SizeBucket.UNKNOWN);
        Assertions.assertThat(requestType.getValue()).isEqualTo(RequestType.OTHER);
    }

    @Test
    @DisplayName("should strip source metadata from cropped responses")
    public void shouldStripSourceMetadata() throws NotImplementedException, IOException, InterruptedException {
        // Source metadata describes the original capture, so it is wrong for every derived image:
        // after cropping, Orientation still refers to the full frame. Browsers apply it per
        // <img>, which made tiled viewers rotate every tile around its own centre (BAV-2632).
        for (String format : List.of("jpg", "png", "webp", "tif")) {
            var source = exifOrientedSource();
            var fixture = loader.loadImage(source);
            assertThat(hasField(fixture, "exif-data"))
                    .as("fixture carries EXIF")
                    .isTrue();
            assertThat(fixture.getInt("orientation")).as("fixture is rotated").isEqualTo(8);

            // 1:1 crop of a region, i.e. exactly the request a tiled viewer makes when zoomed in
            var request =
                    new ImageRequest(source.identifier(), IIIFVersion.V3, "0,0,64,64", "64,", "0", "default", format);
            var info = loader.getImageInfo(source);

            var encoded = processor.encodeImage(processor.processImage(source, request), info, request);
            var decoded = VImage.newFromBytes(arena, asBytes(encoded.data()));

            assertThat(hasField(decoded, "exif-data"))
                    .as("EXIF data in %s response", format)
                    .isFalse();
            assertThat(hasField(decoded, "exif-ifd0-Orientation"))
                    .as("EXIF orientation in %s response", format)
                    .isFalse();
            // Formats that cannot carry EXIF still have a native orientation tag (TIFF)
            if (hasField(decoded, "orientation")) {
                assertThat(decoded.getInt("orientation"))
                        .as("orientation tag in %s response", format)
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("should strip XMP and IPTC metadata from responses")
    public void shouldStripXmpAndIptcMetadata() throws NotImplementedException, IOException, InterruptedException {
        var ident = "metadata_sample.tif";
        var source = new ImageSource(
                ident,
                new FilesystemResolvedImage(Path.of("src/test/resources/images").resolve(ident)),
                null,
                null);
        var fixture = loader.loadImage(source);
        assertThat(hasField(fixture, "xmp-data")).as("fixture carries XMP").isTrue();
        assertThat(hasField(fixture, "iptc-data")).as("fixture carries IPTC").isTrue();
        var info = loader.getImageInfo(source);

        for (String format : List.of("jpg", "png", "webp", "tif")) {
            var request = new ImageRequest(ident, IIIFVersion.V3, "0,0,64,64", "64,", "0", "default", format);
            var encoded = processor.encodeImage(processor.processImage(source, request), info, request);
            var decoded = VImage.newFromBytes(arena, asBytes(encoded.data()));

            assertThat(hasField(decoded, "xmp-data"))
                    .as("XMP in %s response", format)
                    .isFalse();
            assertThat(hasField(decoded, "iptc-data"))
                    .as("IPTC in %s response", format)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("should ignore a keep encoding option")
    public void shouldIgnoreConfiguredKeepOption() throws NotImplementedException, IOException, InterruptedException {
        // Metadata stripping is not configurable, so `keep: all` in the configuration must not
        // sneak source metadata back into responses.
        var config = config(Map.of("jpg", Map.of("keep", 63)));
        var ignoringProcessor =
                new ImageProcessor(arena, config, loader, new ImageRequestParser(config), runtime, metrics);

        var ident = "metadata_sample.tif";
        var source = new ImageSource(
                ident,
                new FilesystemResolvedImage(Path.of("src/test/resources/images").resolve(ident)),
                null,
                null);
        var info = loader.getImageInfo(source);
        var request = new ImageRequest(ident, IIIFVersion.V3, "0,0,64,64", "64,", "0", "default", "jpg");

        var encoded = ignoringProcessor.encodeImage(ignoringProcessor.processImage(source, request), info, request);
        var decoded = VImage.newFromBytes(arena, asBytes(encoded.data()));
        assertThat(hasField(decoded, "xmp-data")).as("XMP despite keep=all").isFalse();
        assertThat(hasField(decoded, "iptc-data")).as("IPTC despite keep=all").isFalse();
    }

    /// Write an image with EXIF orientation 8 (rotate 270) to a temp file and load it back, so
    /// that it carries real EXIF the way a scanned source would.
    private ImageSource exifOrientedSource() throws IOException {
        Path tmp = Files.createTempFile("wolpi-exif", ".jpg");
        tmp.toFile().deleteOnExit();
        VImage img = VImageHelpers.createEmptyImage(arena, 128, 96, Color.green);
        img.set("orientation", 8);
        img.set("exif-ifd0-Make", "Hasselblad");
        img.writeToFile(tmp.toString());
        return new ImageSource(tmp.getFileName().toString(), new FilesystemResolvedImage(tmp), null, null);
    }

    private static boolean hasField(VImage image, String field) {
        return VipsHelper.image_get_typeof(arena, image.getUnsafeStructAddress(), field) != 0L;
    }

    private static byte[] asBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
