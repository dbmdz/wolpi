package dev.mdz.iiif.wolpi.iiif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.mdz.iiif.wolpi.config.IIIFConfig;
import dev.mdz.iiif.wolpi.config.WolpiConfig;
import dev.mdz.iiif.wolpi.model.image.ImageSize;
import dev.mdz.iiif.wolpi.model.params.CropRectangle;
import dev.mdz.iiif.wolpi.model.params.IIIFQuality;
import dev.mdz.iiif.wolpi.model.params.IIIFVersion;
import dev.mdz.iiif.wolpi.model.params.ImageRequest;
import dev.mdz.iiif.wolpi.model.params.Rotation;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ImageRequestParserTest {

  private WolpiConfig wolpiConfig;

  private ImageRequestParser parser;

  @BeforeEach
  void setUp() {
    IIIFConfig.RotationFeature rotationFeature = new IIIFConfig.RotationFeature(true, true, true);
    IIIFConfig.RegionFeature regionFeature = new IIIFConfig.RegionFeature(true, true, true);
    IIIFConfig.ScalingFeature scalingFeature =
        new IIIFConfig.ScalingFeature(true, true, true, true, true, true);
    IIIFConfig.Features features =
        new IIIFConfig.Features(
            scalingFeature,
            regionFeature,
            rotationFeature,
            false,
            false,
            false,
            false,
            false,
            false);
    IIIFConfig.Limits limits = new IIIFConfig.Limits(0, 0, 0L);
    IIIFConfig.Qualities qualities =
        new IIIFConfig.Qualities("color", List.of("color", "gray", "bitonal"));
    IIIFConfig.Formats formats = new IIIFConfig.Formats(List.of("jpg", "png"), List.of("jpg"));
    IIIFConfig iiifConfig = new IIIFConfig(false, limits, features, qualities, formats);
    wolpiConfig = new WolpiConfig(null, null, iiifConfig, null, null, null, null, null);
    parser = new ImageRequestParser(wolpiConfig);
  }

  private ImageRequestParser parserWithRotation(IIIFConfig.RotationFeature rotationFeature) {
    IIIFConfig originalIIIFConfig = wolpiConfig.iiif();
    IIIFConfig.Features originalFeatures = originalIIIFConfig.features();
    IIIFConfig.Features newFeatures =
        new IIIFConfig.Features(
            originalFeatures.scaling(),
            originalFeatures.region(),
            rotationFeature,
            originalFeatures.profileLinkHeader(),
            originalFeatures.jsonLdMediaType(),
            originalFeatures.cors(),
            originalFeatures.canonicalLinkHeader(),
            originalFeatures.canonicalRedirect(),
            originalFeatures.baseUriRedirect());
    IIIFConfig newIIIFConfig =
        new IIIFConfig(
            originalIIIFConfig.restrictToSizes(),
            originalIIIFConfig.limits(),
            newFeatures,
            originalIIIFConfig.qualities(),
            originalIIIFConfig.formats());
    return new ImageRequestParser(
        new WolpiConfig(
            wolpiConfig.dataDirectory(),
            wolpiConfig.imageBaseDir(),
            newIIIFConfig,
            wolpiConfig.cacheControlHeaders(),
            wolpiConfig.extensions(),
            wolpiConfig.extensionPool(),
            wolpiConfig.packaging(),
            wolpiConfig.encodingOptions()));
  }

  private ImageRequestParser parserWithRegion(IIIFConfig.RegionFeature regionFeature) {
    IIIFConfig originalIIIFConfig = wolpiConfig.iiif();
    IIIFConfig.Features originalFeatures = originalIIIFConfig.features();
    IIIFConfig.Features newFeatures =
        new IIIFConfig.Features(
            originalFeatures.scaling(),
            regionFeature,
            originalFeatures.rotation(),
            originalFeatures.profileLinkHeader(),
            originalFeatures.jsonLdMediaType(),
            originalFeatures.cors(),
            originalFeatures.canonicalLinkHeader(),
            originalFeatures.canonicalRedirect(),
            originalFeatures.baseUriRedirect());
    IIIFConfig newIIIFConfig =
        new IIIFConfig(
            originalIIIFConfig.restrictToSizes(),
            originalIIIFConfig.limits(),
            newFeatures,
            originalIIIFConfig.qualities(),
            originalIIIFConfig.formats());
    return new ImageRequestParser(
        new WolpiConfig(
            wolpiConfig.dataDirectory(),
            wolpiConfig.imageBaseDir(),
            newIIIFConfig,
            wolpiConfig.cacheControlHeaders(),
            wolpiConfig.extensions(),
            wolpiConfig.extensionPool(),
            wolpiConfig.packaging(),
            wolpiConfig.encodingOptions()));
  }

  private ImageRequestParser parserWithScaling(IIIFConfig.ScalingFeature scalingFeature) {
    IIIFConfig originalIIIFConfig = wolpiConfig.iiif();
    IIIFConfig.Features originalFeatures = originalIIIFConfig.features();
    IIIFConfig.Features newFeatures =
        new IIIFConfig.Features(
            scalingFeature,
            originalFeatures.region(),
            originalFeatures.rotation(),
            originalFeatures.profileLinkHeader(),
            originalFeatures.jsonLdMediaType(),
            originalFeatures.cors(),
            originalFeatures.canonicalLinkHeader(),
            originalFeatures.canonicalRedirect(),
            originalFeatures.baseUriRedirect());
    IIIFConfig newIIIFConfig =
        new IIIFConfig(
            originalIIIFConfig.restrictToSizes(),
            originalIIIFConfig.limits(),
            newFeatures,
            originalIIIFConfig.qualities(),
            originalIIIFConfig.formats());
    return new ImageRequestParser(
        new WolpiConfig(
            wolpiConfig.dataDirectory(),
            wolpiConfig.imageBaseDir(),
            newIIIFConfig,
            wolpiConfig.cacheControlHeaders(),
            wolpiConfig.extensions(),
            wolpiConfig.extensionPool(),
            wolpiConfig.packaging(),
            wolpiConfig.encodingOptions()));
  }

  private ImageRequestParser parserWithLimits(IIIFConfig.Limits limits) {
    IIIFConfig originalIIIFConfig = wolpiConfig.iiif();
    IIIFConfig newIIIFConfig =
        new IIIFConfig(
            originalIIIFConfig.restrictToSizes(),
            limits,
            originalIIIFConfig.features(),
            originalIIIFConfig.qualities(),
            originalIIIFConfig.formats());
    return new ImageRequestParser(
        new WolpiConfig(
            wolpiConfig.dataDirectory(),
            wolpiConfig.imageBaseDir(),
            newIIIFConfig,
            wolpiConfig.cacheControlHeaders(),
            wolpiConfig.extensions(),
            wolpiConfig.extensionPool(),
            wolpiConfig.packaging(),
            wolpiConfig.encodingOptions()));
  }

  private ImageRequestParser parserWithQualities(IIIFConfig.Qualities qualities) {
    IIIFConfig originalIIIFConfig = wolpiConfig.iiif();
    IIIFConfig newIIIFConfig =
        new IIIFConfig(
            originalIIIFConfig.restrictToSizes(),
            originalIIIFConfig.limits(),
            originalIIIFConfig.features(),
            qualities,
            originalIIIFConfig.formats());
    return new ImageRequestParser(
        new WolpiConfig(
            wolpiConfig.dataDirectory(),
            wolpiConfig.imageBaseDir(),
            newIIIFConfig,
            wolpiConfig.cacheControlHeaders(),
            wolpiConfig.extensions(),
            wolpiConfig.extensionPool(),
            wolpiConfig.packaging(),
            wolpiConfig.encodingOptions()));
  }

  @Nested
  @DisplayName("parseRotation")
  class ParseRotation {

    @ParameterizedTest
    @ValueSource(strings = {"0", "90", "180", "270", "360"})
    @DisplayName("should parse valid degrees")
    void shouldParseValidDegrees(String degreesStr) {
      double degrees = Double.parseDouble(degreesStr);

      Rotation rotation = parser.parseRotation(degreesStr);

      assertThat(rotation.degrees()).isEqualTo(degrees % 360);
      assertThat(rotation.mirror()).isFalse();
    }

    @Test
    @DisplayName("should parse mirrored rotation")
    void shouldParseMirroredRotation() {
      Rotation rotation = parser.parseRotation("!90");

      assertThat(rotation.degrees()).isEqualTo(90);
      assertThat(rotation.mirror()).isTrue();
    }

    @Test
    @DisplayName("should throw exception for invalid rotation spec")
    void shouldThrowExceptionForInvalidRotationSpec() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRotation("abc"));
    }

    @Test
    @DisplayName("should throw exception when mirroring is not supported")
    void shouldThrowExceptionWhenMirroringNotSupported() {
      parser = parserWithRotation(new IIIFConfig.RotationFeature(false, true, true));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRotation("!90"));
    }

    @Test
    @DisplayName("should throw exception when rotation is not supported")
    void shouldThrowExceptionWhenRotationNotSupported() {
      parser = parserWithRotation(new IIIFConfig.RotationFeature(true, false, false));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRotation("90"));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRotation("128"));
    }

    @Test
    @DisplayName(
        "should throw exception when arbitrary rotation is not supported for non-90 degree rotation")
    void shouldThrowExceptionWhenArbitraryRotationNotSupported() {
      parser = parserWithRotation(new IIIFConfig.RotationFeature(true, true, false));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRotation("45"));
      assertThat(parser.parseRotation("90").degrees()).isEqualTo(90);
    }

    @Test
    @DisplayName("should handle negative degrees correctly")
    void shouldHandleNegativeDegrees() {
      Rotation rotation = parser.parseRotation("-90");

      assertThat(rotation.degrees()).isEqualTo(270);
      assertThat(rotation.mirror()).isFalse();
    }
  }

  @Nested
  @DisplayName("parseRegion")
  class ParseRegion {

    private final ImageSize sourceSize = new ImageSize(1000, 800);

    @Test
    @DisplayName("should parse 'full' region")
    void fullRegion() {
      CropRectangle result = parser.parseRegion("full", sourceSize);
      assertThat(result.x()).isZero();
      assertThat(result.y()).isZero();
      assertThat(result.width()).isEqualTo(sourceSize.width());
      assertThat(result.height()).isEqualTo(sourceSize.height());
    }

    @Test
    @DisplayName("should parse 'square' region")
    void squareRegion() {
      CropRectangle result = parser.parseRegion("square", sourceSize);

      assertThat(result.width()).isEqualTo(800);
      assertThat(result.height()).isEqualTo(800);
      assertThat(result.x()).isEqualTo(100);
      assertThat(result.y()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"'10,20,100,200', 10,20,100,200", "'0,0,1000,800', 0,0,1000,800"})
    @DisplayName("should parse pixel-based regions")
    void pixelRegions(String spec, int x, int y, int w, int h) {
      CropRectangle result = parser.parseRegion(spec, sourceSize);

      assertThat(result.x()).isEqualTo(x);
      assertThat(result.y()).isEqualTo(y);
      assertThat(result.width()).isEqualTo(w);
      assertThat(result.height()).isEqualTo(h);
    }

    @ParameterizedTest
    @CsvSource({"'pct:50,50,25,25', 500,400,250,200", "'pct:0,0,100,100', 0,0,1000,800"})
    @DisplayName("should parse percentage-based regions")
    void percentageRegions(String spec, int x, int y, int w, int h) {
      CropRectangle result = parser.parseRegion(spec, sourceSize);

      assertThat(result.x()).isEqualTo(x);
      assertThat(result.y()).isEqualTo(y);
      assertThat(result.width()).isEqualTo(w);
      assertThat(result.height()).isEqualTo(h);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "1,2,3",
          "pct:1,2,3",
          "-10,0,100,100",
          "0,-10,100,100",
          "1000,1000,100,100",
          "500,500,600,600",
          "pct:50,50,60,60",
          "pct:-10,10,20,30",
          "pct:110,10,20,30",
          "pct:10,-10,20,30",
          "pct:10,110,20,30",
          "pct:10,10,-10,30",
          "pct:10,10,120,30",
          "pct:10,10,20,-10",
          "pct:10,10,20,120"
        })
    @DisplayName("should throw exception for invalid or out-of-bounds regions")
    void invalidOrOutOfBoundsRegions(String spec) {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRegion(spec, sourceSize));
    }

    @Test
    @DisplayName("should throw exception when pixel region is disabled")
    void pixelRegionDisabled() {
      parser = parserWithRegion(new IIIFConfig.RegionFeature(true, false, true));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRegion("10,20,30,40", sourceSize));
      assertThat(parser.parseRegion("pct:10,20,30,40", sourceSize))
          .isEqualTo(new CropRectangle(100, 160, 300, 320));
    }

    @Test
    @DisplayName("should throw exception when percent region is disabled")
    void percentRegionDisabled() {
      parser = parserWithRegion(new IIIFConfig.RegionFeature(false, true, true));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRegion("pct:10,20,30,40", sourceSize));
    }

    @Test
    @DisplayName("should throw exception when all region features are disabled")
    void allRegionFeaturesDisabled() {
      parser = parserWithRegion(new IIIFConfig.RegionFeature(false, false, false));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRegion("10,20,30,40", sourceSize));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRegion("pct:10,20,30,40", sourceSize));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRegion("square", sourceSize));
    }

    @Test
    @DisplayName("should throw exception when square region is disabled")
    void squareRegionDisabled() {
      parser = parserWithRegion(new IIIFConfig.RegionFeature(true, true, false));

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRegion("square", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for zero width in percent-based crop")
    void zeroWidthPercentCrop() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRegion("pct:10,20,0,40", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for zero height in percent-based crop")
    void zeroHeightPercentCrop() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseRegion("pct:10,20,30,0", sourceSize));
    }
  }

  @Nested
  @DisplayName("parseSize")
  class ParseSize {

    private final ImageSize sourceSize = new ImageSize(1000, 800);

    @Test
    @DisplayName("should parse 'max' for v3")
    void maxV3() throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, "max", sourceSize);
      assertThat(result).isEqualTo(sourceSize);
    }

    @Test
    @DisplayName("should throw exception for 'full' in v3")
    void fullV3() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "full", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for '^' in v2")
    void caretV2() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V2, "^max", sourceSize));
    }

    @Test
    @DisplayName("should parse 'full' for v2")
    void fullV2() throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V2, "full", sourceSize);
      assertThat(result).isEqualTo(sourceSize);
    }

    @Test
    @DisplayName("should apply maxWidth limit to 'max'")
    void maxWidthLimit() throws NotImplementedException {
      parser = parserWithLimits(new IIIFConfig.Limits(500, 0, 0L));
      ImageSize result = parser.parseSize(IIIFVersion.V3, "max", sourceSize);
      assertThat(result.width()).isEqualTo(500);
      assertThat(result.height()).isEqualTo(400);
    }

    @Test
    @DisplayName("should apply maxHeight limit to 'max'")
    void maxHeightLimit() throws NotImplementedException {
      parser = parserWithLimits(new IIIFConfig.Limits(0, 300, 0L));
      ImageSize result = parser.parseSize(IIIFVersion.V3, "max", sourceSize);
      assertThat(result.width()).isEqualTo(375);
      assertThat(result.height()).isEqualTo(300);
    }

    @Test
    @DisplayName("should apply maxArea limit to 'max'")
    void maxAreaLimit() throws NotImplementedException {
      parser = parserWithLimits(new IIIFConfig.Limits(0, 0, 100000L));
      ImageSize result = parser.parseSize(IIIFVersion.V3, "max", sourceSize);
      assertThat(result.width()).isEqualTo(353);
      assertThat(result.height()).isEqualTo(282);
    }

    @ParameterizedTest
    @CsvSource({"'500,', 500, 400", "'1000,', 1000, 800"})
    @DisplayName("should scale by width")
    void scaleByWidth(String spec, int expectedWidth, int expectedHeight)
        throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, spec, sourceSize);
      assertThat(result.width()).isEqualTo(expectedWidth);
      assertThat(result.height()).isEqualTo(expectedHeight);
    }

    @ParameterizedTest
    @CsvSource({" ',400', 500, 400", " ',800', 1000, 800"})
    @DisplayName("should scale by height")
    void scaleByHeight(String spec, int expectedWidth, int expectedHeight)
        throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, spec, sourceSize);
      assertThat(result.width()).isEqualTo(expectedWidth);
      assertThat(result.height()).isEqualTo(expectedHeight);
    }

    @Test
    @DisplayName("should scale by percent")
    void scaleByPercent() throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, "pct:50", sourceSize);
      assertThat(result.width()).isEqualTo(500);
      assertThat(result.height()).isEqualTo(400);
    }

    @Test
    @DisplayName("should throw exception if upscaling is not allowed")
    void upscaleNotAllowed() {
      parser =
          parserWithScaling(new IIIFConfig.ScalingFeature(true, true, true, true, true, false));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "2000,", sourceSize));
    }

    @Test
    @DisplayName("should allow upscaling with caret")
    void upscaleAllowed() throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, "^2000,", sourceSize);
      assertThat(result.width()).isEqualTo(2000);
      assertThat(result.height()).isEqualTo(1600);
    }

    @ParameterizedTest
    @CsvSource({"'^500,', 500, 400", "'^1000,', 1000, 800"})
    @DisplayName("should scale by width with upscaling")
    void scaleByWidthUpscaling(String spec, int expectedWidth, int expectedHeight)
        throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, spec, sourceSize);
      assertThat(result.width()).isEqualTo(expectedWidth);
      assertThat(result.height()).isEqualTo(expectedHeight);
    }

    @ParameterizedTest
    @CsvSource({" '^,400', 500, 400", " '^,800', 1000, 800"})
    @DisplayName("should scale by height with upscaling")
    void scaleByHeightUpscaling(String spec, int expectedWidth, int expectedHeight)
        throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, spec, sourceSize);
      assertThat(result.width()).isEqualTo(expectedWidth);
      assertThat(result.height()).isEqualTo(expectedHeight);
    }

    @ParameterizedTest
    @CsvSource({"'^pct:50', 500, 400", "'^pct:100', 1000, 800"})
    @DisplayName("should scale by percent with upscaling")
    void scaleByPercentUpscaling(String spec, int expectedWidth, int expectedHeight)
        throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, spec, sourceSize);
      assertThat(result.width()).isEqualTo(expectedWidth);
      assertThat(result.height()).isEqualTo(expectedHeight);
    }

    @ParameterizedTest
    @CsvSource({"'500,400', 500, 400", "'1000,800', 1000, 800"})
    @DisplayName("should scale by arbitrary dimensions")
    void scaleByArbitraryDimensions(String spec, int expectedWidth, int expectedHeight)
        throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, spec, sourceSize);
      assertThat(result.width()).isEqualTo(expectedWidth);
      assertThat(result.height()).isEqualTo(expectedHeight);
    }

    @ParameterizedTest
    @CsvSource({"'^500,400', 500, 400", "'^1000,800', 1000, 800"})
    @DisplayName("should scale by arbitrary dimensions with upscaling")
    void scaleByArbitraryDimensionsUpscaling(String spec, int expectedWidth, int expectedHeight)
        throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, spec, sourceSize);
      assertThat(result.width()).isEqualTo(expectedWidth);
      assertThat(result.height()).isEqualTo(expectedHeight);
    }

    @ParameterizedTest
    @CsvSource({"'!500,400', 500, 400", "'!1000,800', 1000, 800"})
    @DisplayName("should scale by confined dimensions")
    void scaleByConfinedDimensions(String spec, int expectedWidth, int expectedHeight)
        throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, spec, sourceSize);
      assertThat(result.width()).isEqualTo(expectedWidth);
      assertThat(result.height()).isEqualTo(expectedHeight);
    }

    @ParameterizedTest
    @CsvSource({"'^!500,400', 500, 400", "'^!1000,800', 1000, 800"})
    @DisplayName("should scale by confined dimensions with upscaling")
    void scaleByConfinedDimensionsUpscaling(String spec, int expectedWidth, int expectedHeight)
        throws NotImplementedException {
      ImageSize result = parser.parseSize(IIIFVersion.V3, spec, sourceSize);
      assertThat(result.width()).isEqualTo(expectedWidth);
      assertThat(result.height()).isEqualTo(expectedHeight);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "100", "100,abc", "0,0", "100,0", "0,100"})
    @DisplayName("should throw exception for invalid size spec")
    void invalidSizeSpec(String spec) {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, spec, sourceSize));
    }

    @Test
    @DisplayName("should throw exception if width scaling is disabled")
    void widthScalingDisabled() {
      parser =
          parserWithScaling(new IIIFConfig.ScalingFeature(true, true, false, true, true, true));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "500,", sourceSize));
    }

    @Test
    @DisplayName("should throw exception if height scaling is disabled")
    void heightScalingDisabled() {
      parser =
          parserWithScaling(new IIIFConfig.ScalingFeature(true, false, true, true, true, true));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, ",500", sourceSize));
    }

    @Test
    @DisplayName("should throw exception if percent scaling is disabled")
    void percentScalingDisabled() {
      parser =
          parserWithScaling(new IIIFConfig.ScalingFeature(true, true, true, false, true, true));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "pct:50", sourceSize));
    }

    @Test
    @DisplayName("should throw exception if arbitrary dimensions scaling is disabled")
    void arbitraryDimensionsScalingDisabled() {
      parser =
          parserWithScaling(new IIIFConfig.ScalingFeature(true, true, true, true, false, true));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "500,400", sourceSize));
    }

    @Test
    @DisplayName("should throw exception if confined width height scaling is disabled")
    void confinedWidthHeightScalingDisabled() {
      parser =
          parserWithScaling(new IIIFConfig.ScalingFeature(false, true, true, true, true, true));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "!500,400", sourceSize));
    }

    @Test
    @DisplayName("should throw exception if upscaling without caret is attempted")
    void upscalingWithoutCaret() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "2000,", new ImageSize(100, 100)));
    }

    @Test
    @DisplayName("should throw exception if requested width exceeds max width limit")
    void requestedWidthExceedsLimit() {
      parser = parserWithLimits(new IIIFConfig.Limits(100, 0, 0L));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "200,", sourceSize));
    }

    @Test
    @DisplayName("should throw exception if requested height exceeds max height limit")
    void requestedHeightExceedsLimit() {
      parser = parserWithLimits(new IIIFConfig.Limits(0, 100, 0L));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, ",200", sourceSize));
    }

    @Test
    @DisplayName("should throw exception if requested area exceeds max area limit")
    void requestedAreaExceedsLimit() {
      parser = parserWithLimits(new IIIFConfig.Limits(0, 0, 10000L));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "200,200", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for zero width in width-based scaling")
    void zeroWidthWidthScaling() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "0,", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for zero height in height-based scaling")
    void zeroHeightHeightScaling() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, ",0", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for scale out of range in percent-based scaling (too low)")
    void scaleOutOfRangePercentScalingTooLow() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "pct:0", sourceSize));
    }

    @Test
    @DisplayName(
        "should throw exception for scale out of range in percent-based scaling (too high)")
    void scaleOutOfRangePercentScalingTooHigh() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "pct:101", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for invalid confined dimensions spec")
    void invalidConfinedDimensionsSpec() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "!100", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for zero width in confined dimensions")
    void zeroWidthConfinedDimensions() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "!0,100", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for zero height in confined dimensions")
    void zeroHeightConfinedDimensions() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "!100,0", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for zero width in arbitrary dimensions")
    void zeroWidthArbitraryDimensions() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "0,100", sourceSize));
    }

    @Test
    @DisplayName("should throw exception for zero height in arbitrary dimensions")
    void zeroHeightArbitraryDimensions() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "100,0", sourceSize));
    }

    @Test
    @DisplayName("should throw exception if upscaling is not supported and no caret is used")
    void upscalingNotSupportedNoCaret() {
      parser =
          parserWithScaling(new IIIFConfig.ScalingFeature(true, true, true, true, true, false));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> parser.parseSize(IIIFVersion.V3, "2000,", sourceSize));
    }

    @Test
    @DisplayName("should apply maxWidth limit to '^max' when original is smaller")
    void caretMaxWithMaxWidthLimitAndSmallerOriginal() throws NotImplementedException {
      parser = parserWithLimits(new IIIFConfig.Limits(1200, 0, 0L));
      ImageSize result = parser.parseSize(IIIFVersion.V3, "^max", new ImageSize(1000, 800));
      assertThat(result.width()).isEqualTo(1200);
      assertThat(result.height()).isEqualTo(960);
    }

    @Test
    @DisplayName("should apply maxHeight limit to '^max' when original is smaller")
    void caretMaxWithMaxHeightLimitAndSmallerOriginal() throws NotImplementedException {
      parser = parserWithLimits(new IIIFConfig.Limits(0, 1000, 0L));
      ImageSize result = parser.parseSize(IIIFVersion.V3, "^max", new ImageSize(1000, 800));
      assertThat(result.width()).isEqualTo(1250);
      assertThat(result.height()).isEqualTo(1000);
    }

    @Test
    @DisplayName("should throw exception for accidental upscaling when upscaling is not supported")
    void accidentalUpscalingNotSupported() {
      parser =
          parserWithScaling(new IIIFConfig.ScalingFeature(true, true, true, true, true, false));
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(
              () -> parser.parseSize(IIIFVersion.V3, "2000,1600", new ImageSize(1000, 800)));
    }
  }

  @Nested
  @DisplayName("parseQuality")
  class ParseQuality {

    @ParameterizedTest
    @ValueSource(strings = {"color", "gray", "bitonal"})
    @DisplayName("should parse valid qualities")
    void validQualities(String qualityStr) {
      IIIFQuality quality = parser.parseQuality(qualityStr);
      assertThat(quality.name().toLowerCase()).isEqualTo(qualityStr);
    }

    @Test
    @DisplayName("should throw exception for invalid quality spec")
    void invalidQualitySpec() {
      assertThatExceptionOfType(IllegalStateException.class)
          .isThrownBy(() -> parser.parseQuality("invalid"));
    }
  }

  @Nested
  @DisplayName("isRequestForUncroppedAndDownScaledImage")
  class IsRequestForUncroppedAndDownScaledImage {

    @ParameterizedTest
    @CsvSource({"full, 500,", "full, pct:50"})
    @DisplayName("should return true for uncropped and downscaled images")
    void shouldReturnTrue(String regionSpec, String sizeSpec) {
      assertThat(parser.isRequestForUncroppedAndDownScaledImage(regionSpec, sizeSpec)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"10,20,30,40, 500,", "full, full", "full, max", "full, ^max"})
    @DisplayName("should return false for cropped or non-downscaled images")
    void shouldReturnFalse(String regionSpec, String sizeSpec) {
      assertThat(parser.isRequestForUncroppedAndDownScaledImage(regionSpec, sizeSpec)).isFalse();
    }
  }

  @Nested
  @DisplayName("toCanonicalForm")
  class ToCanonicalForm {

    private final ImageSize sourceSize = new ImageSize(1000, 800);

    @Test
    @DisplayName("should canonicalize full region and max size")
    void canonicalizeFullRegionMaxSize() {
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V3, "0,0,1000,800", "1000,800", "0", "default", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.cropSpec()).isEqualTo("full");
      assertThat(canonical.sizeSpec()).isEqualTo("max");
    }

    @Test
    @DisplayName("should canonicalize pixel region to full if it matches source size")
    void canonicalizePixelRegionToFull() {
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V3, "0,0,1000,800", "max", "0", "default", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.cropSpec()).isEqualTo("full");
    }

    @Test
    @DisplayName("should canonicalize size to full for v2 if it matches source size")
    void canonicalizeSizeToFullV2() {
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V2, "full", "1000,800", "0", "default", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.sizeSpec()).isEqualTo("full");
    }

    @Test
    @DisplayName("should return null for invalid request parts")
    void returnNullForInvalidRequest() {
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V3, "invalid", "max", "0", "default", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical).isNull();
    }

    @Test
    @DisplayName("should canonicalize pixel region")
    void canonicalizePixelRegion() {
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V3, "10,20,300,400", "max", "0", "default", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.cropSpec()).isEqualTo("10,20,300,400");
    }

    @Test
    @DisplayName("should canonicalize size to ^max for v3 when upscaling and max limits apply")
    void canonicalizeSizeToCaretMaxV3() {
      parser = parserWithLimits(new IIIFConfig.Limits(2000, 1600, 0L));
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V3, "full", "^max", "0", "default", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.sizeSpec()).isEqualTo("^max");
    }

    @Test
    @DisplayName("should canonicalize size to width-only for v2 when aspect ratio matches")
    void canonicalizeSizeToWidthOnlyV2() {
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V2, "full", "500,", "0", "default", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.sizeSpec()).isEqualTo("500,");
    }

    @Test
    @DisplayName("should canonicalize size to ^w,h for v3 when upscaling")
    void canonicalizeSizeToCaretWHV3() {
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V3, "full", "^2000,1600", "0", "default", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.sizeSpec()).isEqualTo("^2000,1600");
    }

    @Test
    @DisplayName("should canonicalize size to w,h")
    void canonicalizeSizeToWH() {
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V3, "full", "500,400", "0", "default", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.sizeSpec()).isEqualTo("500,400");
    }

    @Test
    @DisplayName("should canonicalize mirrored rotation")
    void canonicalizeMirroredRotation() {
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V3, "full", "max", "!450", "default", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.rotationSpec()).isEqualTo("!90");
    }

    @Test
    @DisplayName("should canonicalize default quality")
    void canonicalizeDefaultQuality() {
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V3, "full", "max", "0", "color", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.qualitySpec()).isEqualTo("default");
    }

    @Test
    @DisplayName("should canonicalize non-default quality")
    void canonicalizeNonDefaultQuality() {
      parser = parserWithQualities(new IIIFConfig.Qualities("color", List.of("color", "gray")));
      ImageRequest request =
          new ImageRequest("id", IIIFVersion.V3, "full", "max", "0", "gray", "jpg");
      ImageRequest canonical = parser.toCanonicalForm(request, sourceSize);
      assertThat(canonical.qualitySpec()).isEqualTo("gray");
    }
  }
}
