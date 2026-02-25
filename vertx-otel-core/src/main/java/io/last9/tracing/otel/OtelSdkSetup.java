package io.last9.tracing.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.instrumentation.runtimemetrics.java8.Classes;
import io.opentelemetry.instrumentation.runtimemetrics.java8.Cpu;
import io.opentelemetry.instrumentation.runtimemetrics.java8.GarbageCollector;
import io.opentelemetry.instrumentation.runtimemetrics.java8.MemoryPools;
import io.opentelemetry.instrumentation.runtimemetrics.java8.Threads;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared OpenTelemetry SDK setup used by both Vert.x 3 and Vert.x 4 launchers.
 *
 * <p>Auto-configures the OpenTelemetry SDK from {@code OTEL_*} environment variables,
 * ensures W3C trace context propagation is active (healing fat-jar shading issues),
 * installs the Logback OTLP appender for log export, and registers JVM runtime metrics.
 *
 * <h2>Resource attributes added automatically</h2>
 * <p>Process, host, and OS attributes are contributed via SPI by {@code opentelemetry-resources}:
 * {@code process.pid}, {@code process.runtime.*}, {@code host.name}, {@code host.arch},
 * {@code os.type}, {@code os.description}.
 *
 * <h2>JVM metrics (requires {@code OTEL_METRICS_EXPORTER=otlp})</h2>
 * <p>The following JVM metrics are registered and exported when a metrics exporter is
 * configured (no-op otherwise):
 * <ul>
 *   <li>{@code jvm.memory.used}, {@code jvm.memory.committed}, {@code jvm.memory.max} — heap + non-heap</li>
 *   <li>{@code jvm.gc.duration} — GC pause time histogram</li>
 *   <li>{@code jvm.thread.count} — live thread count by state</li>
 *   <li>{@code jvm.cpu.time}, {@code jvm.cpu.recent_utilization} — CPU usage</li>
 *   <li>{@code jvm.class.count}, {@code jvm.class.loaded}, {@code jvm.class.unloaded}</li>
 * </ul>
 */
public final class OtelSdkSetup {

    private static final Logger log = LoggerFactory.getLogger(OtelSdkSetup.class);

    private OtelSdkSetup() {
        // Utility class
    }

    /**
     * Auto-configure OpenTelemetry SDK from OTEL_* environment variables.
     *
     * <p>This method:
     * <ol>
     *   <li>Builds and registers the SDK as the global instance (process/host/OS attributes via SPI)</li>
     *   <li>Verifies W3C trace context propagation is configured</li>
     *   <li>Installs the Logback OpenTelemetry appender for log export</li>
     *   <li>Registers JVM runtime metric observers</li>
     * </ol>
     *
     * @return a fully configured OpenTelemetry instance
     */
    public static OpenTelemetry initialize() {
        String serviceName = getEnvOrDefault("OTEL_SERVICE_NAME", "unknown-service");
        String otlpEndpoint = getEnvOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318");

        log.info("=== OpenTelemetry Auto-Configuration ===");
        log.info("Service: {}", serviceName);
        log.info("OTLP Endpoint: {}", otlpEndpoint);

        // 1. Auto-configure SDK from OTEL_* env vars.
        //    Process/host/OS resource attributes (process.pid, host.name, os.type, etc.) are
        //    contributed automatically by the SPI providers in opentelemetry-resources:
        //    ProcessResourceProvider, HostResourceProvider, OsResourceProvider.
        //
        //    telemetry.distro.name is set explicitly so Last9's OTel Collector transform
        //    processor can identify spans from this library and apply semconv remapping
        //    (e.g. http.response.status_code → http.status_code).
        Resource distroResource = Resource.create(Attributes.of(
                AttributeKey.stringKey("telemetry.distro.name"),
                "opentelemetry-java-instrumentation"));

        OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                // Default to http/protobuf so the bundled JDK sender is used.
                // The default gRPC protocol requires okhttp3 which is NOT bundled.
                // Customers can override with OTEL_EXPORTER_OTLP_PROTOCOL=grpc if they
                // add okhttp3 to their own classpath.
                .addPropertiesSupplier(() -> {
                    if (System.getenv("OTEL_EXPORTER_OTLP_PROTOCOL") == null) {
                        return java.util.Map.of("otel.exporter.otlp.protocol", "http/protobuf");
                    }
                    return java.util.Map.of();
                })
                .addResourceCustomizer((resource, config) -> resource.merge(distroResource))
                .setResultAsGlobal()
                .build()
                .getOpenTelemetrySdk();

        log.info("OpenTelemetry SDK initialized successfully");

        // 2. Verify propagators are configured for distributed tracing (traceparent header).
        //    AutoConfigure should set up W3C by default, but fat-jar shading can strip
        //    META-INF/services and silently break propagation.
        OpenTelemetry openTelemetry = ensurePropagators(sdk);

        // 3. Install OpenTelemetry Logback appender for log export
        OpenTelemetryAppender.install(openTelemetry);
        log.info("Logback OpenTelemetry appender installed for log export");

        // 4. Register JVM runtime metric observers.
        //    These are no-ops when OTEL_METRICS_EXPORTER is unset (default "none").
        //    Set OTEL_METRICS_EXPORTER=otlp to export to the configured OTLP endpoint.
        registerJvmMetrics(openTelemetry);
        log.info("JVM runtime metrics registered (memory, GC, threads, CPU, classes)");

        log.info("=== OpenTelemetry Ready ===");
        return openTelemetry;
    }

    /**
     * Registers JVM metric observers against the supplied OpenTelemetry instance.
     * Each {@code registerObservers()} call attaches an {@code ObservableGauge} or
     * {@code ObservableCounter} to the SDK MeterProvider — they are no-ops when the
     * metrics exporter is set to {@code none} (the default).
     */
    private static void registerJvmMetrics(OpenTelemetry openTelemetry) {
        Classes.registerObservers(openTelemetry);
        Cpu.registerObservers(openTelemetry);
        GarbageCollector.registerObservers(openTelemetry);
        MemoryPools.registerObservers(openTelemetry);
        Threads.registerObservers(openTelemetry);
    }

    /**
     * Verify W3C trace context propagators are configured. If auto-configuration failed to
     * register them (e.g. fat-jar shading stripped META-INF/services), rebuild the SDK with
     * the W3C propagator explicitly set.
     */
    private static OpenTelemetry ensurePropagators(OpenTelemetrySdk sdk) {
        TextMapPropagator propagator = sdk.getPropagators().getTextMapPropagator();
        if (propagator.fields().contains("traceparent")) {
            log.info("W3C trace context propagation configured (traceparent header enabled)");
            return sdk;
        }
        log.warn("W3C trace context propagator not found in auto-configuration — "
                + "this can happen when fat-jar shading strips META-INF/services. "
                + "Registering W3CTraceContextPropagator explicitly.");
        return OpenTelemetrySdk.builder()
                .setTracerProvider(sdk.getSdkTracerProvider())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
