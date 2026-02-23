package io.last9.tracing.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared OpenTelemetry SDK setup used by both Vert.x 3 and Vert.x 4 launchers.
 *
 * <p>Auto-configures the OpenTelemetry SDK from {@code OTEL_*} environment variables,
 * ensures W3C trace context propagation is active (healing fat-jar shading issues),
 * and installs the Logback OTLP appender for log export.
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
     *   <li>Verifies W3C trace context propagation is configured</li>
     *   <li>Installs the Logback OpenTelemetry appender for log export</li>
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

        // 1. Auto-configure OpenTelemetry SDK from OTEL_* environment variables.
        //    Set telemetry.distro.name so that OTel Collector transform processors
        //    (which map new semconv attributes like http.request.method to legacy
        //    names like http.method) can match on this resource attribute.
        OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addResourceCustomizer((resource, config) ->
                        resource.merge(Resource.builder()
                                .put(AttributeKey.stringKey("telemetry.distro.name"),
                                        "opentelemetry-java-instrumentation")
                                .build()))
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

        log.info("=== OpenTelemetry Ready ===");
        return openTelemetry;
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
