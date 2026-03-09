package io.last9.tracing.otel.v3;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.SemanticAttributes;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that CoreTracedRouter (core Vert.x API) creates correct SERVER spans
 * with trace isolation, matching the same guarantees as the RxJava2 TracedRouter.
 */
@ExtendWith(VertxExtension.class)
class CoreTracedRouterTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;
    private WebClient webClient;
    private int port;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();

        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);

        Router router = CoreTracedRouter.create(vertx, otel.getOpenTelemetry());

        router.get("/api/fast").handler(ctx ->
                ctx.response().end("fast"));

        router.get("/api/slow").handler(ctx ->
                vertx.setTimer(50, id -> ctx.response().end("slow")));

        router.get("/api/users/:id").handler(ctx ->
                ctx.response().end("user-" + ctx.pathParam("id")));

        router.post("/api/data").handler(ctx ->
                ctx.response().end("got: " + ctx.getBody()));

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, ar -> {
                    if (ar.succeeded()) {
                        port = ar.result().actualPort();
                        testContext.completeNow();
                    } else {
                        testContext.failNow(ar.cause());
                    }
                });

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (vertx != null) {
            CountDownLatch latch = new CountDownLatch(1);
            vertx.close(v -> latch.countDown());
            latch.await(5, TimeUnit.SECONDS);
        }
        otel.shutdown();
    }

    @Test
    void createsServerSpanWithCorrectAttributes(VertxTestContext testContext) {
        webClient.get(port, "localhost", "/api/fast").send(ar -> {
            testContext.verify(() -> {
                assertThat(ar.succeeded()).isTrue();
                assertThat(ar.result().statusCode()).isEqualTo(200);

                waitForSpans(1);
                List<SpanData> serverSpans = getServerSpans();
                assertThat(serverSpans).hasSize(1);

                SpanData span = serverSpans.get(0);
                assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
                assertThat(span.getAttributes().get(SemanticAttributes.HTTP_REQUEST_METHOD))
                        .isEqualTo("GET");
                assertThat(span.getAttributes().get(SemanticAttributes.URL_PATH))
                        .isEqualTo("/api/fast");
                assertThat(span.getAttributes().get(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE))
                        .isEqualTo(200L);
                assertThat(span.getAttributes().get(SemanticAttributes.HTTP_ROUTE))
                        .isEqualTo("/api/fast");
            });
            testContext.completeNow();
        });
    }

    @Test
    void sequentialRequestsProduceIndependentTraces(VertxTestContext testContext) {
        webClient.get(port, "localhost", "/api/fast").send(ar1 -> {
            webClient.get(port, "localhost", "/api/fast").send(ar2 -> {
                webClient.get(port, "localhost", "/api/fast").send(ar3 -> {
                    testContext.verify(() -> {
                        waitForSpans(3);
                        List<SpanData> serverSpans = getServerSpans();
                        assertThat(serverSpans).hasSize(3);

                        Set<String> traceIds = serverSpans.stream()
                                .map(SpanData::getTraceId)
                                .collect(Collectors.toSet());
                        assertThat(traceIds)
                                .as("Each request should have a unique trace ID")
                                .hasSize(3);
                    });
                    testContext.completeNow();
                });
            });
        });
    }

    @Test
    void serverSpansDoNotNestUnderEachOther(VertxTestContext testContext) {
        webClient.get(port, "localhost", "/api/fast").send(ar1 -> {
            webClient.get(port, "localhost", "/api/slow").send(ar2 -> {
                webClient.get(port, "localhost", "/api/fast").send(ar3 -> {
                    testContext.verify(() -> {
                        waitForSpans(3);
                        List<SpanData> serverSpans = getServerSpans();
                        assertThat(serverSpans).hasSize(3);

                        Set<String> serverSpanIds = serverSpans.stream()
                                .map(s -> s.getSpanContext().getSpanId())
                                .collect(Collectors.toSet());

                        for (SpanData span : serverSpans) {
                            String parentSpanId = span.getParentSpanContext().getSpanId();
                            assertThat(serverSpanIds)
                                    .as("SERVER span '%s' should not parent under another SERVER span",
                                            span.getName())
                                    .doesNotContain(parentSpanId);
                        }
                    });
                    testContext.completeNow();
                });
            });
        });
    }

    @Test
    void traceparentHonouredButNotLeakedToNextRequest(VertxTestContext testContext) {
        String foreignTraceId = "0af7651916cd43dd8448eb211c80319c";
        String foreignParentId = "b7ad6b7169203331";
        String traceparent = "00-" + foreignTraceId + "-" + foreignParentId + "-01";

        webClient.get(port, "localhost", "/api/fast")
                .putHeader("traceparent", traceparent)
                .send(ar1 -> {
                    webClient.get(port, "localhost", "/api/fast").send(ar2 -> {
                        testContext.verify(() -> {
                            waitForSpans(2);
                            List<SpanData> serverSpans = getServerSpans();
                            assertThat(serverSpans).hasSize(2);

                            SpanData tracedSpan = serverSpans.stream()
                                    .filter(s -> s.getTraceId().equals(foreignTraceId))
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError(
                                            "Expected a span with the foreign trace ID"));
                            assertThat(tracedSpan.getParentSpanContext().getSpanId())
                                    .isEqualTo(foreignParentId);

                            SpanData independentSpan = serverSpans.stream()
                                    .filter(s -> !s.getTraceId().equals(foreignTraceId))
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError(
                                            "Expected a span with an independent trace ID"));
                            assertThat(independentSpan.getTraceId())
                                    .as("Second request should not inherit the foreign trace")
                                    .isNotEqualTo(foreignTraceId);
                        });
                        testContext.completeNow();
                    });
                });
    }

    @Test
    void spanNameUpdatedWithRoutePattern(VertxTestContext testContext) {
        webClient.get(port, "localhost", "/api/users/42").send(ar -> {
            testContext.verify(() -> {
                waitForSpans(1);
                List<SpanData> serverSpans = getServerSpans();
                assertThat(serverSpans).hasSize(1);

                SpanData span = serverSpans.get(0);
                assertThat(span.getName()).isEqualTo("GET /api/users/:id");
                assertThat(span.getAttributes().get(SemanticAttributes.HTTP_ROUTE))
                        .isEqualTo("/api/users/:id");
            });
            testContext.completeNow();
        });
    }

    @Test
    void postRequestBodyIsAvailable(VertxTestContext testContext) {
        webClient.post(port, "localhost", "/api/data")
                .sendBuffer(Buffer.buffer("hello"), ar -> {
                    testContext.verify(() -> {
                        assertThat(ar.succeeded()).isTrue();
                        assertThat(ar.result().bodyAsString()).isEqualTo("got: hello");

                        waitForSpans(1);
                        List<SpanData> serverSpans = getServerSpans();
                        assertThat(serverSpans).hasSize(1);
                        assertThat(serverSpans.get(0).getAttributes()
                                .get(SemanticAttributes.HTTP_REQUEST_METHOD)).isEqualTo("POST");
                    });
                    testContext.completeNow();
                });
    }

    @Test
    void instrumentExistingIsIdempotent(VertxTestContext testContext) {
        Router router = Router.router(vertx);
        CoreTracedRouter.instrumentExisting(router, otel.getOpenTelemetry());
        CoreTracedRouter.instrumentExisting(router, otel.getOpenTelemetry()); // should be no-op

        router.get("/api/test").handler(ctx -> ctx.response().end("ok"));

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, listenAr -> {
                    int testPort = listenAr.result().actualPort();
                    webClient.get(testPort, "localhost", "/api/test").send(ar -> {
                        testContext.verify(() -> {
                            waitForSpans(1);
                            List<SpanData> serverSpans = getServerSpans();
                            // Should be 1, not 2 — idempotent instrumentation
                            assertThat(serverSpans).hasSize(1);
                        });
                        testContext.completeNow();
                    });
                });
    }

    @Test
    void manySequentialRequestsAllHaveUniqueTraces(VertxTestContext testContext) {
        int count = 10;
        sendSequential(count, 0, testContext);
    }

    private void sendSequential(int total, int current, VertxTestContext testContext) {
        if (current >= total) {
            testContext.verify(() -> {
                waitForSpans(total);
                List<SpanData> serverSpans = getServerSpans();
                assertThat(serverSpans).hasSize(total);

                Set<String> traceIds = serverSpans.stream()
                        .map(SpanData::getTraceId)
                        .collect(Collectors.toSet());
                assertThat(traceIds)
                        .as("All %d requests should have unique trace IDs", total)
                        .hasSize(total);
            });
            testContext.completeNow();
            return;
        }

        webClient.get(port, "localhost", "/api/fast").send(ar ->
                sendSequential(total, current + 1, testContext));
    }

    // ---- Helpers ----

    private List<SpanData> getServerSpans() {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.SERVER)
                .toList();
    }

    private void waitForSpans(int minCount) {
        for (int i = 0; i < 100; i++) {
            long serverSpanCount = spanExporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER)
                    .count();
            if (serverSpanCount >= minCount) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
    }
}
