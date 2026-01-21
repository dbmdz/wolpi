package dev.mdz.wolpi.metrics;

import dev.mdz.wolpi.iiif.model.IIIFVersion;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class WolpiMetrics {

    private final MeterRegistry registry;

    private final Meter.MeterProvider<Counter> imagesProcessedCounter;
    private final Meter.MeterProvider<Counter> sourceLoadsCounter;
    private final Meter.MeterProvider<Counter> vipsErrorsCounter;
    private final Meter.MeterProvider<Timer> extensionExecutionTimer;
    private final Meter.MeterProvider<Counter> extensionInvocationsCounter;
    private final Meter.MeterProvider<Counter> extensionFailureCounter;

    public WolpiMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.imagesProcessedCounter = Counter.builder("wolpi.images.processed.total")
                .description("Images successfully processed and encoded")
                .withRegistry(registry);
        this.sourceLoadsCounter = Counter.builder("wolpi.source.loads.total")
                .description("Image loads by source type")
                .withRegistry(registry);
        this.vipsErrorsCounter = Counter.builder("wolpi.vips.errors.total")
                .description("Errors from libvips operations")
                .withRegistry(registry);
        this.extensionInvocationsCounter = Counter.builder("wolpi.extension.invocations.total")
                .description("Extension hook invocations")
                .withRegistry(registry);
        this.extensionExecutionTimer = Timer.builder("wolpi.extension.execution.seconds")
                .description("Time taken to execute extension hooks")
                .withRegistry(registry);
        this.extensionFailureCounter = Counter.builder("wolpi.extension.errors.total")
                .description("Extension hook errors")
                .withRegistry(registry);
    }

    /// Timer for image processing operations with detailed tags
    public static class ImageProcessingTimer {
        private final Timer.Sample sample;
        private final MeterRegistry registry;
        private final String format;
        private final SizeBucket outputSize;
        private final SizeBucket croppedArea;
        private final RequestType requestType;
        private final boolean usedFastPath;

        ImageProcessingTimer(
                Timer.Sample sample,
                MeterRegistry registry,
                String format,
                SizeBucket outputSize,
                SizeBucket croppedArea,
                RequestType requestType,
                boolean usedFastPath) {
            this.sample = sample;
            this.registry = registry;
            this.format = format;
            this.outputSize = outputSize;
            this.croppedArea = croppedArea;
            this.requestType = requestType;
            this.usedFastPath = usedFastPath;
        }

        public void stop() {
            sample.stop(Timer.builder("wolpi.image.processing.seconds")
                    .description("Time to process and encode image")
                    .tag("format", format)
                    .tag("output_size", outputSize.toTag())
                    .tag("cropped_area", croppedArea.toTag())
                    .tag("request_type", requestType.toTag())
                    .tag("fast_path", usedFastPath ? "shrink_on_load" : "none")
                    .register(registry));
        }
    }

    public ImageProcessingTimer startImageProcessingTimer(
            String format,
            SizeBucket outputSize,
            SizeBucket croppedArea,
            RequestType requestType,
            boolean usedFastPath) {
        return new ImageProcessingTimer(
                Timer.start(registry), registry, format, outputSize, croppedArea, requestType, usedFastPath);
    }

    public void incrementImagesProcessed(String format, String quality, IIIFVersion iiifVersion) {
        this.imagesProcessedCounter
                .withTags(
                        "format",
                        format,
                        "quality",
                        quality,
                        "iiif_version",
                        iiifVersion.name().toLowerCase(Locale.ENGLISH))
                .increment();
    }

    public void incrementVipsErrors(String context) {
        this.vipsErrorsCounter.withTag("context", context).increment();
    }

    public void incrementValidationRequests() {
        Counter.builder("wolpi.validation.requests.total")
                .description("IIIF validation test image requests")
                .register(registry)
                .increment();
    }

    public void incrementSourceLoads(String sourceType) {
        this.sourceLoadsCounter.withTag("source_type", sourceType).increment();
    }

    public Timer.Sample startExtensionTimer() {
        return Timer.start();
    }

    public void recordExtensionExecution(Timer.Sample sample, String extensionName, String hookType) {
        sample.stop(this.extensionExecutionTimer.withTags("extension_name", extensionName, "hook_type", hookType));
    }

    public void incrementExtensionInvocations(String extensionName, String hookType) {
        this.extensionInvocationsCounter
                .withTags("extension_name", extensionName, "hook_type", hookType)
                .increment();
    }

    public void incrementExtensionErrors(String extensionName, String hookType) {
        this.extensionFailureCounter
                .withTags("extension_name", extensionName, "hook_type", hookType)
                .increment();
    }
}
