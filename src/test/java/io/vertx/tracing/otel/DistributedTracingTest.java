package io.vertx.tracing.otel;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.core.http.HttpServer;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests distributed tracing via traceparent header propagation.
 *
 * Verifies that:
 * - Incoming requests with traceparent create child spans (same trace ID)
 * - Outgoing HTTP client requests carry the traceparent header
 */
@ExtendWith(VertxExtension.class)
class DistributedTracingTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;
    private int mainPort;
    private int backendPort;
    private HttpServer backendServer;
    private final List<String> capturedTraceparentHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        RxJavaPlugins.reset();
        resetInstalledFlag();

        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();

        VertxOptions vertxOptions = new VertxOptions()
                .setTracingOptions(new OpenTelemetryOptions(otel.getOpenTelemetry()));
        vertx = Vertx.vertx(vertxOptions);

        RxJava3ContextPropagation.install();

        // Start a backend server that captures the traceparent header
        // (simulates Service B receiving a call from our service)
        vertx.createHttpServer()
                .requestHandler(req -> {
                    String traceparent = req.getHeader("traceparent");
                    if (traceparent != null) {
                        capturedTraceparentHeaders.add(traceparent);
                    }
                    req.response().end("backend-ok");
                })
                .rxListen(0)
                .flatMap(server -> {
                    backendServer = server;
                    backendPort = server.actualPort();

                    // Start main server with TracedRouter
                    Router router = TracedRouter.create(vertx);

                    // Route that calls the backend (simulates Service A → this → Service B)
                    router.get("/api/call-backend").handler(ctx -> {
                        vertx.createHttpClient()
                                .rxRequest(HttpMethod.GET, backendPort, "localhost", "/backend-endpoint")
                                .flatMap(req -> req.rxSend())
                                .flatMap(resp -> resp.rxBody())
                                .subscribe(
                                        body -> ctx.response().end("forwarded:" + body.toString()),
                                        err -> ctx.response().setStatusCode(500).end(err.getMessage())
                                );
                    });

                    // Simple echo route for incoming traceparent test
                    router.get("/api/echo").handler(ctx -> ctx.response().end("echo-ok"));

                    return vertx.createHttpServer()
                            .requestHandler(router)
                            .rxListen(0);
                })
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
        capturedTraceparentHeaders.clear();
        if (vertx != null) {
            vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        }
        otel.shutdown();
    }

    @Test
    void incomingTraceparentCreatesChildSpan(VertxTestContext testContext) throws Exception {
        // Simulate Service A sending a request with an existing traceparent
        String parentTraceId = "0af7651916cd43dd8448eb211c80319c";
        String parentSpanId = "b7ad6b7169203331";
        String traceparent = "00-" + parentTraceId + "-" + parentSpanId + "-01";

        vertx.createHttpClient()
                .rxRequest(HttpMethod.GET, mainPort, "localhost", "/api/echo")
                .flatMap(req -> {
                    req.putHeader("traceparent", traceparent);
                    return req.rxSend();
                })
                .flatMap(resp -> resp.rxBody())
                .subscribe(
                        body -> {
                            testContext.verify(() -> {
                                assertThat(body.toString()).isEqualTo("echo-ok");

                                waitForSpans(1);
                                List<SpanData> spans = spanExporter.getFinishedSpanItems();
                                SpanData serverSpan = spans.stream()
                                        .filter(s -> s.getKind() == SpanKind.SERVER)
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError("No SERVER span found"));

                                // The server span should belong to the SAME trace as the incoming traceparent
                                assertThat(serverSpan.getTraceId())
                                        .as("Server span trace ID should match incoming traceparent trace ID")
                                        .isEqualTo(parentTraceId);

                                // The server span's parent should be the incoming parent span
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
    void outgoingRequestCarriesTraceparent(VertxTestContext testContext) throws Exception {
        // Call the main server which in turn calls the backend
        vertx.createHttpClient()
                .rxRequest(HttpMethod.GET, mainPort, "localhost", "/api/call-backend")
                .flatMap(req -> req.rxSend())
                .flatMap(resp -> resp.rxBody())
                .subscribe(
                        body -> {
                            testContext.verify(() -> {
                                assertThat(body.toString()).isEqualTo("forwarded:backend-ok");

                                // The backend should have received a traceparent header
                                assertThat(capturedTraceparentHeaders)
                                        .as("Backend should receive traceparent header from outgoing request")
                                        .isNotEmpty();

                                String traceparent = capturedTraceparentHeaders.get(0);
                                // traceparent format: 00-<traceId>-<spanId>-<flags>
                                assertThat(traceparent)
                                        .as("traceparent should follow W3C format")
                                        .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

                                // Verify the spans share the same trace
                                waitForSpans(2);
                                List<SpanData> spans = spanExporter.getFinishedSpanItems();
                                SpanData serverSpan = spans.stream()
                                        .filter(s -> s.getKind() == SpanKind.SERVER)
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError("No SERVER span found"));

                                // The traceparent sent to backend should contain the same trace ID
                                String traceIdInHeader = traceparent.split("-")[1];
                                assertThat(traceIdInHeader)
                                        .as("Outgoing traceparent trace ID should match server span trace ID")
                                        .isEqualTo(serverSpan.getTraceId());
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void endToEndTracePropagation(VertxTestContext testContext) throws Exception {
        // Simulate full chain: Service A → Main Service → Backend (Service B)
        // All should share the same trace ID across the entire chain
        String parentTraceId = "abcdef1234567890abcdef1234567890";
        String parentSpanId = "1234567890abcdef";
        String traceparent = "00-" + parentTraceId + "-" + parentSpanId + "-01";

        vertx.createHttpClient()
                .rxRequest(HttpMethod.GET, mainPort, "localhost", "/api/call-backend")
                .flatMap(req -> {
                    req.putHeader("traceparent", traceparent);
                    return req.rxSend();
                })
                .flatMap(resp -> resp.rxBody())
                .subscribe(
                        body -> {
                            testContext.verify(() -> {
                                assertThat(body.toString()).isEqualTo("forwarded:backend-ok");

                                // Backend received traceparent
                                assertThat(capturedTraceparentHeaders).isNotEmpty();
                                String backendTraceparent = capturedTraceparentHeaders.get(0);
                                String backendTraceId = backendTraceparent.split("-")[1];

                                // The trace ID should propagate end-to-end
                                assertThat(backendTraceId)
                                        .as("Trace ID should propagate from Service A → Main → Backend")
                                        .isEqualTo(parentTraceId);

                                // All spans in the chain should share the same trace ID
                                waitForSpans(2);
                                List<SpanData> spans = spanExporter.getFinishedSpanItems();
                                List<SpanData> serverSpans = spans.stream()
                                        .filter(s -> s.getKind() == SpanKind.SERVER)
                                        .toList();

                                assertThat(serverSpans)
                                        .as("Should have at least one server span")
                                        .isNotEmpty();

                                // All server spans should belong to the same trace
                                for (SpanData span : serverSpans) {
                                    assertThat(span.getTraceId())
                                            .as("All spans should carry the incoming trace ID")
                                            .isEqualTo(parentTraceId);
                                }
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
            var field = RxJava3ContextPropagation.class.getDeclaredField("installed");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) field.get(null)).set(false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset installed flag", e);
        }
    }
}
