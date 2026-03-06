package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.lang.reflect.Method;

/**
 * Test utility that registers an in-memory OpenTelemetry SDK as the global instance.
 * Required for testing agent helpers that use {@link GlobalOpenTelemetry#get()}.
 *
 * <p>Call {@link #setUp()} in {@code @BeforeEach} and {@link #tearDown()} in {@code @AfterEach}.
 */
class GlobalOtelTestSetup {

    private final InMemorySpanExporter spanExporter;
    private final SdkTracerProvider tracerProvider;
    private final OpenTelemetrySdk openTelemetry;

    GlobalOtelTestSetup() {
        this.spanExporter = InMemorySpanExporter.create();
        this.tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        this.openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    void setUp() {
        resetGlobalOpenTelemetry();
        GlobalOpenTelemetry.set(openTelemetry);
    }

    void tearDown() {
        tracerProvider.shutdown();
        resetGlobalOpenTelemetry();
    }

    InMemorySpanExporter getSpanExporter() {
        return spanExporter;
    }

    OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    private static void resetGlobalOpenTelemetry() {
        try {
            Method resetMethod = GlobalOpenTelemetry.class.getDeclaredMethod("resetForTest");
            resetMethod.setAccessible(true);
            resetMethod.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset GlobalOpenTelemetry for test", e);
        }
    }
}
