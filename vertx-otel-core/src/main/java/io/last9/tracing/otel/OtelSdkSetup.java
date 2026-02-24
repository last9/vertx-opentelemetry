package io.last9.tracing.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
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
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.resources.ResourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;

/**
 * Shared OpenTelemetry SDK setup used by both Vert.x 3 and Vert.x 4 launchers.
 *
 * <p>Auto-configures the OpenTelemetry SDK from {@code OTEL_*} environment variables,
 * ensures W3C trace context propagation is active (healing fat-jar shading issues),
 * installs the Logback OTLP appender for log export, and registers JVM runtime metrics.
 *
 * <h2>Resource attributes added automatically</h2>
 * <p>The following process/host/OS attributes are attached to every span as resource
 * attributes (equivalent to what the OpenTelemetry Java agent provides):
 * <ul>
 *   <li>{@code process.pid} — JVM process ID</li>
 *   <li>{@code process.runtime.name} — e.g. {@code OpenJDK Runtime Environment}</li>
 *   <li>{@code process.runtime.version} — e.g. {@code 17.0.10+7}</li>
 *   <li>{@code process.runtime.description} — VM name + version from JMX</li>
 *   <li>{@code host.name} — local hostname</li>
 *   <li>{@code os.type} — {@code linux}, {@code darwin}, {@code windows}, etc.</li>
 *   <li>{@code os.description} — OS name + version</li>
 * </ul>
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
     *   <li>Builds and registers the SDK as the global instance</li>
     *   <li>Attaches process/host/OS resource attributes to every span</li>
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

        // 1. Auto-configure SDK from OTEL_* env vars, merging process/host/OS resource
        //    attributes so every span carries the same context the Java agent would provide.
        Resource processResource = buildProcessResource();
        log.info("Process resource attributes:");
        processResource.getAttributes().forEach(
                (key, value) -> log.info("  resource.{} = {}", key.getKey(), value));

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
                .addResourceCustomizer((resource, config) ->
                        resource.merge(processResource))
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
     * Builds a Resource containing process, host, and OS attributes equivalent to those
     * provided by the OpenTelemetry Java agent's built-in resource detectors.
     */
    private static Resource buildProcessResource() {
        ResourceBuilder builder = Resource.builder()
                // OTel Collector transform processors use this to map new semconv → legacy names
                .put(AttributeKey.stringKey("telemetry.distro.name"),
                        "opentelemetry-java-instrumentation");

        // process.pid — Java 9+ ProcessHandle; always available on supported JVMs (17+)
        try {
            builder.put(AttributeKey.longKey("process.pid"), ProcessHandle.current().pid());
        } catch (Throwable ignored) {
            // fallback: parse "pid@hostname" from RuntimeMXBean.getName()
            try {
                String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
                int at = runtimeName.indexOf('@');
                if (at > 0) {
                    builder.put(AttributeKey.longKey("process.pid"),
                            Long.parseLong(runtimeName.substring(0, at)));
                }
            } catch (Throwable ignored2) { /* best-effort */ }
        }

        // process.runtime.* — JVM identity information
        builder.put(AttributeKey.stringKey("process.runtime.name"),
                System.getProperty("java.runtime.name", "unknown"));
        builder.put(AttributeKey.stringKey("process.runtime.version"),
                System.getProperty("java.runtime.version", "unknown"));
        builder.put(AttributeKey.stringKey("process.runtime.description"),
                ManagementFactory.getRuntimeMXBean().getVmName()
                        + " " + ManagementFactory.getRuntimeMXBean().getVmVersion());

        // host.name — local hostname (DNS or /etc/hostname)
        try {
            builder.put(AttributeKey.stringKey("host.name"),
                    InetAddress.getLocalHost().getHostName());
        } catch (Throwable ignored) { /* no network config; skip */ }

        // os.type — normalised to OTel semconv values
        String rawOs = System.getProperty("os.name", "").toLowerCase();
        String osType = rawOs.contains("win") ? "windows"
                : rawOs.contains("mac") || rawOs.contains("darwin") ? "darwin"
                : rawOs.contains("linux") ? "linux"
                : rawOs.contains("freebsd") ? "freebsd"
                : rawOs.contains("solaris") || rawOs.contains("sunos") ? "solaris"
                : rawOs.contains("aix") ? "aix"
                : "unknown";
        builder.put(AttributeKey.stringKey("os.type"), osType);
        builder.put(AttributeKey.stringKey("os.description"),
                System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""));

        return builder.build();
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
