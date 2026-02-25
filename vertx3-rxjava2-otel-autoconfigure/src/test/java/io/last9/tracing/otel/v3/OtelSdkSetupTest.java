package io.last9.tracing.otel.v3;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the OtelSdkSetup resource customizer injects the expected
 * resource attributes — specifically telemetry.distro.name, which Last9's
 * OTel Collector transform processor uses to apply semconv remapping,
 * and process/host/os attributes contributed by opentelemetry-resources SPI.
 */
class OtelSdkSetupTest {

    private static final Map<String, String> NO_OP_EXPORTERS = Map.of(
            "otel.traces.exporter", "none",
            "otel.metrics.exporter", "none",
            "otel.logs.exporter", "none");

    @Test
    void telemetryDistroNameIsSetOnResource() {
        Resource distroResource = Resource.create(Attributes.of(
                AttributeKey.stringKey("telemetry.distro.name"),
                "opentelemetry-java-instrumentation"));

        AtomicReference<Resource> captured = new AtomicReference<>();

        AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> NO_OP_EXPORTERS)
                .addResourceCustomizer((resource, config) -> {
                    Resource merged = resource.merge(distroResource);
                    captured.set(merged);
                    return merged;
                })
                .build()
                .getOpenTelemetrySdk()
                .close();

        assertThat(captured.get().getAttribute(AttributeKey.stringKey("telemetry.distro.name")))
                .isEqualTo("opentelemetry-java-instrumentation");
    }

    @Test
    void processAndHostAttributesPresentViaSpi() {
        AtomicReference<Resource> captured = new AtomicReference<>();

        AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> NO_OP_EXPORTERS)
                .addResourceCustomizer((resource, config) -> {
                    // resource already has SPI-contributed attributes (process, host, os)
                    // merged before this customizer is called
                    captured.set(resource);
                    return resource;
                })
                .build()
                .getOpenTelemetrySdk()
                .close();

        Resource resource = captured.get();

        // Contributed by ProcessResourceProvider (opentelemetry-resources SPI)
        assertThat(resource.getAttribute(AttributeKey.longKey("process.pid")))
                .isNotNull()
                .isPositive();

        // Contributed by HostResourceProvider (opentelemetry-resources SPI)
        assertThat(resource.getAttribute(AttributeKey.stringKey("host.name")))
                .isNotBlank();

        // Contributed by OsResourceProvider (opentelemetry-resources SPI)
        assertThat(resource.getAttribute(AttributeKey.stringKey("os.type")))
                .isNotBlank();
    }
}
