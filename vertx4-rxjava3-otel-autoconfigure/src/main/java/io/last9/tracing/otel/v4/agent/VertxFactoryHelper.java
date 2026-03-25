package io.last9.tracing.otel.v4.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper for {@link VertxFactoryAdvice}. Injects OpenTelemetry tracing options
 * and Micrometer metrics options into the {@code VertxOptions} held by the
 * {@code VertxBuilder} before the Vert.x instance is created.
 *
 * <p>Uses reflection to access the {@code options} field on the builder,
 * then calls {@code setTracingOptions()} and {@code setMetricsOptions()}
 * on the mutable {@code VertxOptions} object.
 */
public final class VertxFactoryHelper {

    private static final Logger log = LoggerFactory.getLogger(VertxFactoryHelper.class);
    private static final AtomicBoolean INJECTED = new AtomicBoolean(false);

    private VertxFactoryHelper() {}

    /**
     * Called from {@link VertxFactoryAdvice} when {@code VertxBuilder.init()} is entered.
     *
     * @param builder the VertxBuilder instance (typed as Object for advice compatibility)
     */
    public static void injectOptions(Object builder) {
        // Only inject once — apps may create multiple Vertx instances, but
        // we only configure the first one.
        if (!INJECTED.compareAndSet(false, true)) {
            return;
        }

        try {
            // Get the VertxOptions from the builder via reflection
            Field optionsField = builder.getClass().getDeclaredField("options");
            optionsField.setAccessible(true);
            Object options = optionsField.get(builder);

            if (options == null) {
                log.warn("Vertx4Agent: VertxOptions is null, skipping injection");
                return;
            }

            OpenTelemetry otel = GlobalOpenTelemetry.get();

            // Inject TracingOptions — enables VertxTracer SPI for HTTP, EventBus, SQL, etc.
            java.lang.reflect.Method setTracing = options.getClass()
                    .getMethod("setTracingOptions", io.vertx.core.tracing.TracingOptions.class);
            setTracing.invoke(options, new OpenTelemetryOptions(otel));
            log.info("Vertx4Agent: TracingOptions injected (HTTP server/client, EventBus, SQL auto-tracing enabled)");

            // Inject MicrometerMetricsOptions — enables Vert.x internal metrics via OTel bridge
            try {
                io.micrometer.core.instrument.MeterRegistry meterRegistry =
                        OpenTelemetryMeterRegistry.builder(otel).build();
                MicrometerMetricsOptions metricsOptions = new MicrometerMetricsOptions()
                        .setMicrometerRegistry(meterRegistry)
                        .setJvmMetricsEnabled(false)  // OtelSdkSetup already registers JVM metrics
                        .setEnabled(true);

                java.lang.reflect.Method setMetrics = options.getClass()
                        .getMethod("setMetricsOptions", io.vertx.core.metrics.MetricsOptions.class);
                setMetrics.invoke(options, metricsOptions);
                log.info("Vertx4Agent: MicrometerMetricsOptions injected (Vert.x internal metrics enabled)");
            } catch (Throwable t) {
                log.warn("Vertx4Agent: Micrometer metrics injection skipped: {}", t.getMessage());
            }

        } catch (Throwable t) {
            log.warn("Vertx4Agent: Failed to inject options: {}", t.getMessage());
            INJECTED.set(false); // Allow retry
        }
    }
}
