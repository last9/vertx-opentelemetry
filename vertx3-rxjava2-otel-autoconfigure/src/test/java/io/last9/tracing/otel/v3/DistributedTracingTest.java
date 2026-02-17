package io.last9.tracing.otel.v3;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.plugins.RxJavaPlugins;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests distributed tracing via incoming traceparent header propagation.
 *
 * <p>Verifies that incoming requests with a traceparent header create child spans
 * (same trace ID). Note: Unlike Vert.x 4, outgoing HTTP requests do NOT automatically
 * carry the traceparent header in Vert.x 3 (no VertxTracer SPI).
 */
@ExtendWith(VertxExtension.class)
class DistributedTracingTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;
    private WebClient webClient;
    private int mainPort;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        RxJavaPlugins.reset();
        resetInstalledFlag();

        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();

        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);

        RxJava2ContextPropagation.install();

        Router router = TracedRouter.create(vertx, otel.getOpenTelemetry());

        router.get("/api/echo").handler(ctx -> ctx.response().end("echo-ok"));

        vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .subscribe(
                        server -> {
                            mainPort = server.actualPort();
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void tearDown() {
        RxJavaPlugins.reset();
        if (vertx != null) {
            vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        }
        otel.shutdown();
    }

    @Test
    void incomingTraceparentCreatesChildSpan(VertxTestContext testContext) throws Exception {
        String parentTraceId = "0af7651916cd43dd8448eb211c80319c";
        String parentSpanId = "b7ad6b7169203331";
        String traceparent = "00-" + parentTraceId + "-" + parentSpanId + "-01";

        webClient.get(mainPort, "localhost", "/api/echo")
                .putHeader("traceparent", traceparent)
                .rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.bodyAsString()).isEqualTo("echo-ok");

                                waitForSpans(1);
                                List<SpanData> spans = spanExporter.getFinishedSpanItems();
                                SpanData serverSpan = spans.stream()
                                        .filter(s -> s.getKind() == SpanKind.SERVER)
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError("No SERVER span found"));

                                assertThat(serverSpan.getTraceId())
                                        .as("Server span trace ID should match incoming traceparent trace ID")
                                        .isEqualTo(parentTraceId);

                                assertThat(serverSpan.getParentSpanId())
                                        .as("Server span should be a child of the incoming parent span")
                                        .isEqualTo(parentSpanId);
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void requestWithoutTraceparentCreatesNewTrace(VertxTestContext testContext) throws Exception {
        webClient.get(mainPort, "localhost", "/api/echo")
                .rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.bodyAsString()).isEqualTo("echo-ok");

                                waitForSpans(1);
                                SpanData serverSpan = spanExporter.getFinishedSpanItems().stream()
                                        .filter(s -> s.getKind() == SpanKind.SERVER)
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError("No SERVER span found"));

                                // Should have a valid trace ID (not all zeros)
                                assertThat(serverSpan.getTraceId())
                                        .matches("[0-9a-f]{32}")
                                        .isNotEqualTo("00000000000000000000000000000000");

                                // Should have no parent (root span)
                                assertThat(serverSpan.getParentSpanId())
                                        .isEqualTo("0000000000000000");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    // ---- Helpers ----

    private void waitForSpans(int minCount) {
        for (int i = 0; i < 50; i++) {
            if (spanExporter.getFinishedSpanItems().size() >= minCount) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
    }

    private void resetInstalledFlag() {
        try {
            var field = RxJava2ContextPropagation.class.getDeclaredField("installed");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) field.get(null)).set(false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset installed flag", e);
        }
    }
}
