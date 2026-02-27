package io.last9.tracing.otel.v4;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying that Vert.x internal metrics are collected and
 * flow through the Micrometer → OTel bridge into the in-memory metric reader.
 *
 * <p>Each test creates a full Vert.x instance with both
 * {@link OpenTelemetryOptions} (tracing) and {@link MicrometerMetricsOptions}
 * (metrics) wired to the same OTel SDK instance, matching the production setup
 * in {@link OtelLauncher}.
 */
@ExtendWith(VertxExtension.class)
class VertxMetricsTest {

    private TestOtelSetup otel;
    private MeterRegistry micrometerRegistry;
    private Vertx vertx;
    private int port;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        otel = new TestOtelSetup();

        // Mirror OtelLauncher: bridge Micrometer into the same OTel SDK instance
        micrometerRegistry = OpenTelemetryMeterRegistry.builder(otel.getOpenTelemetry()).build();

        VertxOptions options = new VertxOptions()
                .setTracingOptions(new OpenTelemetryOptions(otel.getOpenTelemetry()))
                .setMetricsOptions(new MicrometerMetricsOptions()
                        .setMicrometerRegistry(micrometerRegistry)
                        .setJvmMetricsEnabled(false)
                        .setEnabled(true));

        vertx = Vertx.vertx(options);

        Router router = TracedRouter.create(vertx);
        router.get("/ping").handler(ctx -> ctx.response().end("pong"));

        vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .subscribe(
                        server -> {
                            port = server.actualPort();
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        }
        otel.shutdown();
    }

    @Test
    void vertxMetricsOptionsConfiguresSuccessfully() {
        // If MicrometerMetricsOptions failed to wire, Vert.x would have thrown during setUp.
        // Reaching this point means metrics were accepted without error.
        assertThat(vertx).isNotNull();
        assertThat(micrometerRegistry).isNotNull();
    }

    @Test
    void httpServerMetricsAreEmittedAfterRequest(VertxTestContext testContext) throws Exception {
        // Make one HTTP request
        vertx.createHttpClient()
                .rxRequest(HttpMethod.GET, port, "localhost", "/ping")
                .flatMap(req -> req.rxSend())
                .flatMap(resp -> resp.rxBody())
                .subscribe(
                        body -> {
                            testContext.verify(() ->
                                    assertThat(body.toString()).isEqualTo("pong"));
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();

        // Allow metric export to flush
        Thread.sleep(200);

        Collection<MetricData> metrics = otel.getMetricReader().collectAllMetrics();
        boolean hasHttpMetric = metrics.stream()
                .anyMatch(m -> m.getName().startsWith("vertx.http.server"));

        assertThat(hasHttpMetric)
                .as("Expected at least one vertx.http.server.* metric after an HTTP request. "
                        + "Found metrics: " + metrics.stream().map(MetricData::getName).toList())
                .isTrue();
    }

    @Test
    void eventLoopLagProbeIntegratesWithOtelMetrics() throws Exception {
        EventLoopLagProbe.install(vertx.getDelegate(), micrometerRegistry);

        // Wait for the probe to fire at least twice
        Thread.sleep(300);

        Collection<MetricData> metrics = otel.getMetricReader().collectAllMetrics();
        boolean hasLagMetric = metrics.stream()
                .anyMatch(m -> m.getName().equals("vertx.eventloop.lag"));

        assertThat(hasLagMetric)
                .as("vertx.eventloop.lag should appear in OTel metrics after EventLoopLagProbe.install(). "
                        + "Found metrics: " + metrics.stream().map(MetricData::getName).toList())
                .isTrue();
    }

    @Test
    void workerPoolMetricsAreRegistered() throws Exception {
        // Execute a task on the worker pool to trigger pool metrics
        vertx.rxExecuteBlocking(() -> "done").blockingGet();

        Thread.sleep(200);

        Collection<MetricData> metrics = otel.getMetricReader().collectAllMetrics();
        boolean hasPoolMetric = metrics.stream()
                .anyMatch(m -> m.getName().startsWith("vertx.pool"));

        assertThat(hasPoolMetric)
                .as("Expected at least one vertx.pool.* metric after a worker pool execution. "
                        + "Found metrics: " + metrics.stream().map(MetricData::getName).toList())
                .isTrue();
    }
}
