package dev.mdz.wolpi.image;

import app.photofox.vipsffm.VBlob;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VNamedEnum;
import app.photofox.vipsffm.VTarget;
import app.photofox.vipsffm.VipsHelper;
import app.photofox.vipsffm.VipsOption;
import app.photofox.vipsffm.enums.VipsDirection;
import app.photofox.vipsffm.enums.VipsIntent;
import app.photofox.vipsffm.enums.VipsInterpretation;
import app.photofox.vipsffm.enums.VipsOperationRelational;
import app.photofox.vipsffm.enums.VipsPCS;
import app.photofox.vipsffm.enums.VipsSize;
import dev.mdz.wolpi.config.WolpiConfig;
import dev.mdz.wolpi.extension.ExtensionRuntime;
import dev.mdz.wolpi.extension.model.ExtensionHooks;
import dev.mdz.wolpi.iiif.ImageRequestParser;
import dev.mdz.wolpi.iiif.exceptions.NotImplementedException;
import dev.mdz.wolpi.iiif.model.CropRectangle;
import dev.mdz.wolpi.iiif.model.IIIFQuality;
import dev.mdz.wolpi.iiif.model.ImageRequest;
import dev.mdz.wolpi.metrics.ScaleCropMode;
import dev.mdz.wolpi.metrics.WolpiMetrics;
import dev.mdz.wolpi.model.EncodedImage;
import dev.mdz.wolpi.model.ImageInfo;
import dev.mdz.wolpi.model.ImageSize;
import dev.mdz.wolpi.model.ImageSource;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/// ImageProcessor is responsible for processing images according to IIIF Image API requests.
///
/// It handles the whole pipeline of scaling, cropping, rotating, color conversion and output
/// encoding via libvips in an efficient way.
@Component
public class ImageProcessor {
    // Formats for which it is faster to do a thumbnail-then-crop approach for cropped downscale
    // requests, as opposed to loading the full image and cropping in-memory. This is based on
    // empirical testing of libvips' performance characteristics.
    private static final Set<String> SCALE_THEN_CROP_FORMATS =
            Set.of("jpeg", "uhdr", "png", "heif", "pdf", "svg", "webp");

    private final Arena vipsArena;
    private final WolpiConfig wolpiConfig;

    private final ImageRequestParser parser;
    private final ImageLoader loader;

    private final Map<String, List<VipsOption>> formatEncodingOptions;
    private final ExtensionRuntime extensionRuntime;
    private final WolpiMetrics metrics;

    /// Thread-local to track which scale/crop mode was used
    private final ThreadLocal<ScaleCropMode> scaleCropModeUsed =
            ThreadLocal.withInitial(() -> ScaleCropMode.CROP_THEN_SCALE);

    public ImageProcessor(
            Arena vipsArena,
            WolpiConfig wolpiConfig,
            ImageLoader loader,
            ImageRequestParser parser,
            ExtensionRuntime extensionRuntime,
            WolpiMetrics metrics) {
        this.vipsArena = vipsArena;
        this.wolpiConfig = wolpiConfig;
        this.loader = loader;
        this.parser = parser;
        this.formatEncodingOptions = determineEncodingOptions(wolpiConfig.encodingOptions());
        this.extensionRuntime = extensionRuntime;
        this.metrics = metrics;
    }

    /// Convert the encoding options from the config into VipsOption lists for each format.
    private static Map<String, List<VipsOption>> determineEncodingOptions(Map<String, Map<String, Object>> options) {
        // Build a cache for all possible Vips enum values so we can look up enum values
        Map<String, VNamedEnum> vipsEnumCache = new HashMap<>();
        HashSet<Class<?>> subTypes;
        try (ScanResult scanResult = new ClassGraph()
                .enableAllInfo()
                .acceptPackages("app.photofox.vipsffm.enums")
                .scan()) {
            ClassInfoList classInfos = scanResult.getClassesImplementing("app.photofox.vipsffm.VNamedEnum");
            subTypes = new HashSet<>(classInfos.loadClasses());
        }

        for (Class<?> enumClass : subTypes) {
            for (var constant : enumClass.getEnumConstants()) {
                var enumVal = (VNamedEnum) constant;
                vipsEnumCache.put(enumVal.getName(), enumVal);
            }
        }

        // Now map the options for each format
        return options.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().entrySet().stream()
                        .map(ie -> mapToOption(vipsEnumCache, ie.getKey(), ie.getValue()))
                        .toList()));
    }

    /// Map a single encoding option key-value pair to a VipsOption, using the enum cache to resolve
    /// enum values.
    /// @param enumCache Cache of Vips enum values by name
    /// @param key       Option key
    /// @param value     Option value, can be [Integer], [Boolean], [Double], [String],
    ///                  [List<Double>], [List<Float>] or [List<Integer>]
    /// @return VipsOption representing the key-value pair with the proper type
    private static VipsOption mapToOption(Map<String, VNamedEnum> enumCache, String key, Object value) {
        return switch (value) {
            // Primitive types
            case Integer intValue -> VipsOption.Int(key, intValue);
            case Boolean boolValue -> VipsOption.Boolean(key, boolValue);
            case Double doubleValue -> VipsOption.Double(key, doubleValue);
            case Float floatValue -> VipsOption.Double(key, Double.valueOf(floatValue));
            // String values can be either enum values or just strings
            case String strValue -> {
                var enumValue = enumCache.get(strValue);
                if (enumValue != null) {
                    yield VipsOption.Enum(key, enumValue);
                } else {
                    yield VipsOption.String(key, strValue);
                }
            }
            // Multivalued options - only support lists of integers or doubles
            case List<?> listValue -> {
                if (listValue.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Cannot use empty list as encoding option value for key: %s".formatted(key));
                }
                var first = listValue.getFirst();
                if (!listValue.stream().allMatch(v -> v.getClass() == first.getClass())) {
                    throw new IllegalArgumentException(
                            "All values in the list must be of the same type for key: %s".formatted(key));
                }
                yield switch (first) {
                    case Integer _ ->
                        VipsOption.ArrayInt(
                                key, listValue.stream().map(Integer.class::cast).toList());
                    case Double _ ->
                        VipsOption.ArrayDouble(
                                key, listValue.stream().map(Double.class::cast).toList());
                    case Float _ ->
                        VipsOption.ArrayDouble(
                                key,
                                listValue.stream()
                                        .map(v -> Double.valueOf((Float) v))
                                        .toList());
                    default ->
                        throw new IllegalArgumentException(
                                "Unsupported list value type: %s for key: %s".formatted(first.getClass(), key));
                };
            }
            default ->
                throw new IllegalArgumentException(
                        "Unsupported encoding option type: %s for key: %s".formatted(value.getClass(), key));
        };
    }

    /// Process the image from the given ImageSource according to the provided ImageRequest.
    ///
    /// @param imageSource Source to load the image from
    /// @param request     unparsed IIIF Image API request
    public VImage processImage(ImageSource imageSource, ImageRequest request)
            throws IOException, InterruptedException, NotImplementedException {
        // Preload the image (just the header) if necessary to determine the native dimensions or
        // to run through pre-processing
        VImage image = null;
        ImageInfo imageInfo = imageSource.imageInfo();
        ImageSize sourceSize;
        // We need to load the image immediately if an extension intends to preprocess it
        boolean hasCustomPreProcessing = extensionRuntime.hasExtensionsForHook(ExtensionHooks.PREPROCESS_IMAGE);
        boolean hasCustomCrop = extensionRuntime.hasExtensionsForHook(ExtensionHooks.CROP);
        boolean hasCustomScale = extensionRuntime.hasExtensionsForHook(ExtensionHooks.SCALE);
        boolean hasCustomProcessing = hasCustomPreProcessing || hasCustomScale || hasCustomCrop;
        if (hasCustomProcessing || imageInfo == null) {
            image = loader.loadImage(imageSource);
            imageInfo = loader.getImageInfo(image);
            sourceSize = new ImageSize(image.getWidth(), image.getHeight());
        } else {
            sourceSize = imageInfo.nativeSize();
        }

        CropRectangle regionCrop = null;
        if (!hasCustomCrop) {
            regionCrop = parser.parseRegion(request.cropSpec(), sourceSize);
        }
        ImageSize targetSize = null;
        if (!hasCustomScale) {
            targetSize = parser.parseSize(
                    request.version(),
                    request.sizeSpec(),
                    regionCrop == null ? sourceSize : new ImageSize(regionCrop.width(), regionCrop.height()));
        }

        if (!hasCustomPreProcessing
                && parser.isRequestForUncroppedAndDownScaledImage(request.cropSpec(), request.sizeSpec())) {
            // Fast Path: Uncropped downscaled image - we can load directly into the target size without
            // needing to load the full image first
            scaleCropModeUsed.set(ScaleCropMode.SCALE_NO_CROP);
            image = loader.loadImage(imageSource, parser.parseSize(request.version(), request.sizeSpec(), sourceSize));
        } else if (!hasCustomProcessing && shouldUseScaleThenCrop(imageInfo, sourceSize, regionCrop, targetSize)) {
            scaleCropModeUsed.set(ScaleCropMode.SCALE_THEN_CROP);
            image = scaleThenCrop(imageSource, sourceSize, regionCrop, targetSize);
        } else {
            scaleCropModeUsed.set(ScaleCropMode.CROP_THEN_SCALE);
            if (image == null) {
                image = loader.loadImage(imageSource);
            }
            image = cropAndScale(image, request, imageInfo, sourceSize);
        }

        VImage rotated = extensionRuntime.preRotate(image, request.identifier(), imageInfo, request);
        if (rotated == null) {
            rotated = rotateImage(request, image);
        }

        VImage modifiedImage = extensionRuntime.preQuality(rotated, request.identifier(), imageInfo, request);
        if (modifiedImage == null) {
            modifiedImage = changeImageQuality(request, rotated);
        }

        return modifiedImage;
    }

    /// Perform cropping and scaling by first scaling and then cropping.
    ///
    /// This is an optimization for requests that involve cropping a downscaled image without custom
    /// extension logic, where it can be more efficient to do the scaling first and then crop the smaller
    /// image, as opposed to loading the full image and cropping in-memory. This does not apply for
    /// every format, so this method should only be used for image formats that do not have native support
    /// for cropped loads, and where benchmarks have shown that this approach is faster.
    ///
    /// @param imageSource   Source to load the image from
    /// @param sourceSize    Size of the original source image
    /// @param regionCrop    Crop rectangle in the coordinates of the original source image
    /// @param targetSize    Target size to scale to after cropping
    /// @return the cropped and scaled image
    private VImage scaleThenCrop(
            ImageSource imageSource, ImageSize sourceSize, CropRectangle regionCrop, ImageSize targetSize)
            throws IOException, InterruptedException {
        // Size parameter targets the crop region, so we need to calculate the scaling factor
        // from the crop region to the target size, and apply that to the source size to get
        // the size for the initial thumbnail load
        var scaleFactor = ImageDimensionsMath.getScalingFactor(regionCrop.size(), targetSize);
        VImage image = loader.loadImage(imageSource, sourceSize.scale(scaleFactor));
        var loadedSize = new ImageSize(image.getWidth(), image.getHeight());

        // The crop rectangle is defined in the coordinates of the original image, but we need to remap it to the
        // coordinates of the loaded thumbnail image before cropping
        var remappedCrop = ImageDimensionsMath.remapCrop(regionCrop, sourceSize, loadedSize);
        VImage cropped = cropImage(image, remappedCrop, loadedSize);

        // Now we can do a final scale to the (possibly not AR-matching) target size
        return scaleImage(cropped, targetSize);
    }

    /// Perform cropping and scaling with extension hooks.
    ///
    /// This is faster for formats that support native cropped loads (Pyramid TIFs, JPEG2000), and
    /// is necessary when extensions need to hook into the cropping or scaling steps, since we have
    /// to respect the order of operations as defined by the IIIF spec in those cases..
    ///
    /// @param image         the image to crop and scale
    /// @param request       the original IIIF request, used for parsing the crop and size
    /// @param imageInfo     the ImageInfo for the image, used for passing to extensions
    /// @param sourceSize    the size of the image, used for parsing the crop and size params
    /// @return the cropped and scaled image
    private VImage cropAndScale(VImage image, ImageRequest request, ImageInfo imageInfo, ImageSize sourceSize)
            throws NotImplementedException {
        var preprocessed = extensionRuntime.preProcessImage(image, request.identifier(), imageInfo, request);
        if (preprocessed == null) {
            preprocessed = image;
        }

        VImage cropped = extensionRuntime.preCrop(preprocessed, request.identifier(), imageInfo, request);
        if (cropped == null) {
            cropped = cropImage(preprocessed, request, sourceSize);
        }
        ImageSize croppedSize = new ImageSize(cropped.getWidth(), cropped.getHeight());

        VImage scaled = extensionRuntime.preScale(cropped, request.identifier(), imageInfo, request);
        if (scaled == null) {
            scaled = scaleImage(cropped, request, croppedSize);
        }

        return scaled;
    }

    private VImage changeImageQuality(ImageRequest request, VImage image) {
        String qualitySpec = request.qualitySpec();
        if (qualitySpec.equalsIgnoreCase("default")) {
            qualitySpec = wolpiConfig.iiif().qualities().defaultQuality();
        }
        IIIFQuality quality = parser.parseQuality(qualitySpec);

        // Embedded color profiles are converted to sRGB in LAB connection space with perceptual intent
        boolean hasColorProfile =
                VipsHelper.image_get_typeof(vipsArena, image.getUnsafeStructAddress(), "icc-profile-data") != 0;
        if (hasColorProfile) {
            image = image.iccTransform(
                    "srgb",
                    VipsOption.Enum("intent", VipsIntent.INTENT_PERCEPTUAL),
                    VipsOption.Boolean("embedded", true),
                    VipsOption.Enum("pcs", VipsPCS.PCS_LAB));
            image.remove("icc-profile-data");
        }
        var outputInterpretation =
                switch (quality) {
                    case COLOR -> VipsInterpretation.INTERPRETATION_sRGB;
                    case GRAY -> VipsInterpretation.INTERPRETATION_GREY16;
                    case BITONAL -> VipsInterpretation.INTERPRETATION_B_W;
                };
        // FIXME: There should be high-level API in vips-ffm for this, but there isn't yet
        int sourceInterpretation = VipsHelper.image_get_interpretation(image.getUnsafeStructAddress());
        if (outputInterpretation.getRawValue() != sourceInterpretation) {
            return image.colourspace(outputInterpretation);
        }
        return image;
    }

    private VImage rotateImage(ImageRequest request, VImage image) {
        VImage rotated = image;
        var rotation = parser.parseRotation(request.rotationSpec());
        if (rotation.mirror() || rotation.degrees() != 0.0) {
            // Apply mirroring and rotation
            if (rotation.mirror()) {
                rotated = rotated.flip(VipsDirection.DIRECTION_HORIZONTAL);
            }
            if (rotation.degrees() != 0.0) {
                rotated = rotated.rotate(rotation.degrees());
            }
        }
        return rotated;
    }

    /// Returns whether we should use the scale-then-crop appraoch for a request
    private boolean shouldUseScaleThenCrop(
            ImageInfo imageInfo,
            ImageSize sourceSize,
            @Nullable CropRectangle cropRectangle,
            @Nullable ImageSize targetSize) {
        if (cropRectangle == null || targetSize == null) {
            return false;
        }
        // No actual cropping performed?
        if (cropRectangle.width() == sourceSize.width() && cropRectangle.height() == sourceSize.height()) {
            return false;
        }
        // Would it actually be faster with the format?
        if (imageInfo.format() == null || !SCALE_THEN_CROP_FORMATS.contains(imageInfo.format())) {
            return false;
        }
        // Are we actually downscaling? Note that scaling will be done on the **cropped** image, not
        // the original source image
        return ImageDimensionsMath.getScalingFactor(cropRectangle.size(), targetSize) < 1.0;
    }

    /// Scales an image according to the size parameter of an IIIF request.
    private VImage scaleImage(VImage cropped, ImageRequest request, ImageSize sourceSize)
            throws NotImplementedException {
        var scaledSize = parser.parseSize(request.version(), request.sizeSpec(), sourceSize);
        return scaleImage(cropped, scaledSize);
    }

    /// Scales an image to an already parsed target size, skipping work when the size is unchanged.
    private VImage scaleImage(VImage image, ImageSize scaledSize) {
        if (image.getWidth() == scaledSize.width() && image.getHeight() == scaledSize.height()) {
            return image;
        }
        return image.thumbnailImage(
                scaledSize.width(),
                VipsOption.Int("height", scaledSize.height()),
                VipsOption.Enum("size", VipsSize.SIZE_FORCE));
    }

    /// Crops an image to an already parsed crop rectangle.
    private VImage cropImage(VImage preprocessed, CropRectangle cropRectangle, ImageSize sourceSize) {
        if (cropRectangle.width() == sourceSize.width() && cropRectangle.height() == sourceSize.height()) {
            return preprocessed;
        }
        return preprocessed.extractArea(
                cropRectangle.x(), cropRectangle.y(), cropRectangle.width(), cropRectangle.height());
    }

    /// Parses the request region and crops the image accordingly.
    private VImage cropImage(VImage preprocessed, ImageRequest request, ImageSize sourceSize) {
        var cropRectangle = parser.parseRegion(request.cropSpec(), sourceSize);
        return cropImage(preprocessed, cropRectangle, sourceSize);
    }

    /// Encode an image to a target format.
    ///
    /// This is where all the lazy vips operations are actually executed.
    public EncodedImage encodeImage(VImage image, ImageInfo info, ImageRequest request) throws IOException {
        // Determine output size and cropped area for metrics
        var outputSize =
                dev.mdz.wolpi.metrics.SizeBucket.fromDimension(new ImageSize(image.getWidth(), image.getHeight()));
        var cropRectangle = parser.parseRegion(request.cropSpec(), info.nativeSize());
        var croppedArea = dev.mdz.wolpi.metrics.SizeBucket.fromArea(cropRectangle);
        var requestType = dev.mdz.wolpi.metrics.RequestType.classify(
                request.cropSpec(), outputSize, cropRectangle, info.nativeSize());

        var timer = metrics.startImageProcessingTimer(
                request.formatSpec(), outputSize, croppedArea, requestType, scaleCropModeUsed.get());

        try {
            var extensionEncoded = extensionRuntime.preFormat(image, request.identifier(), info, request);

            if (extensionEncoded != null) {
                return new EncodedImage(
                        extensionEncoded.data(), extensionEncoded.contentType(), extensionEncoded.extraHeaders());
            }

            String suffix = request.formatSpec().toLowerCase();

            if (!wolpiConfig.iiif().formats().allowed().contains(suffix)) {
                throw new IllegalArgumentException("Unsupported output format: " + suffix);
            }

            List<VipsOption> options = formatEncodingOptions.getOrDefault(suffix, new ArrayList<>());

            // Force 1bit output for bitonal images in PNG and GIF formats
            if (image.getInt("interpretation") == VipsInterpretation.INTERPRETATION_B_W.getRawValue()) {
                if (suffix.equals("png") || suffix.equals("gif")) {
                    options.add(VipsOption.Int("bitdepth", 1));
                } else {
                    // Other formats do not perform binarization themselves, so we need to threshold the
                    // image
                    image = image.relationalConst(VipsOperationRelational.OPERATION_RELATIONAL_MORE, List.of(128.0));
                }
            }

            String mimeType = Files.probeContentType(Paths.get("image.%s".formatted(suffix)));
            VTarget writeTarget = VTarget.newToMemory(vipsArena);
            VBlob buf;
            if (suffix.equals("pdf")) {
                buf = image.magicksaveBuffer(VipsOption.String("format", "pdf"));
            } else {
                image.writeToTarget(writeTarget, ".%s".formatted(suffix), options.toArray(new VipsOption[0]));
                buf = writeTarget.getBlob();
            }
            return new EncodedImage(
                    buf.asClonedByteBuffer(), mimeType != null ? mimeType : "image/%s".formatted(suffix), null);
        } finally {
            timer.stop();
            scaleCropModeUsed.remove();
        }
    }
}
