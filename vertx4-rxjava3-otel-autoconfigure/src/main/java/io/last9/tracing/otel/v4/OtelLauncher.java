package io.last9.tracing.otel.v4;

import io.last9.tracing.otel.OtelSdkSetup;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import io.vertx.core.Launcher;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom Vert.x 4 Launcher that auto-configures OpenTelemetry.
 *
 * <p>This launcher extends {@link io.vertx.core.Launcher} and configures OpenTelemetry
 * before Vert.x starts. It provides:
 * <ul>
 *   <li>OpenTelemetry SDK auto-configuration from OTEL_* environment variables</li>
 *   <li>Vert.x tracing integration for HTTP server/client, EventBus, SQL client</li>
 *   <li>RxJava3 context propagation hooks</li>
 *   <li>Logback MDC injection for trace_id/span_id</li>
 *   <li>Vert.x internal metrics: event loop lag, worker pool, HTTP server, EventBus</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * <mainClass>io.last9.tracing.otel.v4.OtelLauncher</mainClass>
 * }</pre>
 *
 * <h2>Disabling metrics</h2>
 * <p>Set {@code OTEL_METRICS_EXPORTER=none} to disable all metric export
 * (OTel SDK becomes a no-op for metrics; Vert.x metrics collection still runs
 * but observations are dropped).
 *
 * @see <a href="https://opentelemetry.io/docs/concepts/sdk-configuration/">OpenTelemetry SDK Configuration</a>
 */
public class OtelLauncher extends Launcher {

    private static final Logger log = LoggerFactory.getLogger(OtelLauncher.class);

    // Stored between beforeStartingVertx and afterStartingVertx to pass to EventLoopLagProbe.
    private MeterRegistry meterRegistry;

    public static void main(String[] args) {
        new OtelLauncher().dispatch(args);
    }

    @Override
    public void beforeStartingVertx(VertxOptions options) {
        try {
            // 1. Auto-configure OpenTelemetry SDK (shared setup)
            OpenTelemetry openTelemetry = OtelSdkSetup.initialize();

            // 2. Configure Vert.x to use OpenTelemetry tracing via vertx-opentelemetry
            options.setTracingOptions(new OpenTelemetryOptions(openTelemetry));
            log.info("Vert.x 4 tracing configured (HTTP server/client, EventBus, SQL client)");

            // 3. Install RxJava3 context propagation hooks
            RxJava3ContextPropagation.install();
            log.info("RxJava3 context propagation hooks installed");

            // 4. Configure Vert.x internal metrics via Micrometer → OTel bridge.
            //    OpenTelemetryMeterRegistry bridges Micrometer meters into the OTel SDK instance
            //    already set up above — same OTLP endpoint, same OTEL_* env vars, no extra config.
            //    JVM metrics are disabled here because OtelSdkSetup already registers them via
            //    runtime-telemetry-java8 (avoids duplicate jvm.* metrics).
            meterRegistry = OpenTelemetryMeterRegistry.builder(openTelemetry).build();
            options.setMetricsOptions(new MicrometerMetricsOptions()
                    .setMicrometerRegistry(meterRegistry)
                    .setJvmMetricsEnabled(false)
                    .setEnabled(true));
            log.info("Vert.x internal metrics configured (event loop, worker pool, HTTP server, EventBus)");

            log.info("All HTTP requests, DB queries, and EventBus messages will be traced automatically.");

        } catch (Exception e) {
            log.error("Failed to initialize OpenTelemetry: {}", e.getMessage(), e);
            log.warn("Application will start WITHOUT tracing or metrics.");
        }
    }

    @Override
    public void afterStartingVertx(Vertx vertx) {
        // Install event loop lag probe once Vert.x is fully started.
        // vertx-micrometer-metrics exposes pending task counts but not actual lag time;
        // EventLoopLagProbe fills that gap with a setPeriodic-based measurement.
        if (meterRegistry != null) {
            EventLoopLagProbe.install(vertx, meterRegistry);
        }
    }
}
