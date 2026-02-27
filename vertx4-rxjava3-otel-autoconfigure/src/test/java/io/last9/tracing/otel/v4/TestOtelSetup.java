package io.last9.tracing.otel.v4;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * Shared test utility for creating an in-memory OpenTelemetry SDK.
 * Spans and metrics are captured in memory and can be inspected after test execution.
 */
public class TestOtelSetup {

    private final InMemorySpanExporter spanExporter;
    private final InMemoryMetricReader metricReader;
    private final SdkTracerProvider tracerProvider;
    private final OpenTelemetrySdk openTelemetry;

    public TestOtelSetup() {
        this.spanExporter = InMemorySpanExporter.create();
        this.metricReader = InMemoryMetricReader.create();
        this.tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build();
        this.openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    public OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    public Tracer getTracer() {
        return openTelemetry.getTracer("test");
    }

    public InMemorySpanExporter getSpanExporter() {
        return spanExporter;
    }

    public InMemoryMetricReader getMetricReader() {
        return metricReader;
    }

    public void reset() {
        spanExporter.reset();
    }

    public void shutdown() {
        tracerProvider.shutdown();
    }
}
