package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the event-loop scope leak bug.
 *
 * <p>The bug: when multiple requests overlap on the same Vert.x event loop thread,
 * a scope closed in bodyEndHandler (after response sent) closes the WRONG scope —
 * the one belonging to a later request. This causes CLIENT spans to be orphaned
 * (no parent) or parented under the wrong SERVER span.
 *
 * <p>The fix: close scope immediately when handle() returns (try-with-resources),
 * not in bodyEndHandler. The SPAN stays open until bodyEndHandler, but the scope
 * (ThreadLocal) is cleaned up before the next request arrives.
 *
 * <p>This test verifies that under concurrent load, every CLIENT span created
 * inside a handler correctly parents under its own SERVER span.
 */
@ExtendWith(VertxExtension.class)
class HttpServerScopeLeakTest {

    private GlobalOtelTestSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;
    private WebClient webClient;
    private int port;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        HttpServerAdviceHelper.resetForTest();
        otel = new GlobalOtelTestSetup();
        otel.setUp();
        spanExporter = otel.getSpanExporter();

        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);

        // Handler that simulates a real app: creates a CLIENT span inside the handler
        // (like what JDBC/Redis/Aerospike advice would do) and delays the response.
        // The delay ensures requests OVERLAP on the event loop — request B arrives
        // before request A's response finishes.
        Handler<HttpServerRequest> handler = request -> {
            String path = request.path();

            // Simulate a CLIENT span (DB/Redis/Aerospike call) inside the handler.
            // This span should parent under the SERVER span created by HttpServerAdviceHelper.
            Tracer tracer = GlobalOpenTelemetry.getTracer("test");
            Span clientSpan = tracer.spanBuilder("db-call " + path)
                    .setSpanKind(SpanKind.CLIENT)
                    .startSpan();
            clientSpan.end();

            // Delay response to ensure overlapping requests on the event loop
            io.vertx.core.Vertx delegate = vertx.getDelegate();
            delegate.setTimer(50, id ->
                    request.response()
                            .putHeader("X-Path", path)
                            .end("ok:" + path));
        };

        @SuppressWarnings("unchecked")
        Handler<HttpServerRequest> wrapped =
                (Handler<HttpServerRequest>) HttpServerAdviceHelper.wrapHandler(handler);

        vertx.getDelegate().createHttpServer()
                .requestHandler(wrapped)
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
    void tearDown() {
        if (vertx != null) {
            vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        }
        otel.tearDown();
    }

    /**
     * Fire multiple concurrent requests and verify every CLIENT span parents
     * under its own SERVER span (same trace_id).
     *
     * <p>With the bug (scope closed in bodyEndHandler):
     * - CLIENT spans would have a different trace_id than their SERVER span
     * - Or CLIENT spans would have no parent (root spans)
     *
     * <p>With the fix (scope closed immediately):
     * - Every CLIENT span has the same trace_id as its SERVER span
     */
    @Test
    void concurrentRequestsHaveCorrectParentage(VertxTestContext testContext) throws Exception {
        int requestCount = 5;
        CountDownLatch latch = new CountDownLatch(requestCount);

        // Fire requests concurrently — they all hit the event loop thread
        for (int i = 0; i < requestCount; i++) {
            webClient.get(port, "localhost", "/api/req" + i)
                    .rxSend()
                    .subscribe(
                            resp -> latch.countDown(),
                            err -> {
                                latch.countDown();
                                testContext.failNow(err);
                            }
                    );
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        waitForSpans(requestCount * 2); // SERVER + CLIENT per request

        List<SpanData> allSpans = spanExporter.getFinishedSpanItems();

        List<SpanData> serverSpans = allSpans.stream()
                .filter(s -> s.getKind() == SpanKind.SERVER)
                .collect(Collectors.toList());

        List<SpanData> clientSpans = allSpans.stream()
                .filter(s -> s.getKind() == SpanKind.CLIENT)
                .collect(Collectors.toList());

        testContext.verify(() -> {
            assertThat(serverSpans).hasSize(requestCount);
            assertThat(clientSpans).hasSize(requestCount);

            // Build a map of trace_id → SERVER span for lookup
            Map<String, SpanData> serverByTrace = serverSpans.stream()
                    .collect(Collectors.toMap(
                            s -> s.getTraceId(),
                            s -> s,
                            (a, b) -> a));

            // Every CLIENT span must have a SERVER span with the SAME trace_id.
            // This proves the scope was correct when the CLIENT span was created.
            for (SpanData client : clientSpans) {
                String clientTraceId = client.getTraceId();
                SpanData matchingServer = serverByTrace.get(clientTraceId);

                assertThat(matchingServer)
                        .as("CLIENT span '%s' (trace=%s) must have a SERVER span with same trace_id",
                                client.getName(), clientTraceId)
                        .isNotNull();

                // CLIENT span's parent must be the SERVER span
                assertThat(client.getParentSpanId())
                        .as("CLIENT span '%s' must be a child of SERVER span '%s'",
                                client.getName(), matchingServer.getName())
                        .isEqualTo(matchingServer.getSpanId());
            }

            testContext.completeNow();
        });

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Verify that no CLIENT spans are orphaned root spans.
     * With the scope leak bug, CLIENT spans would have parentSpanId="0000000000000000".
     */
    @Test
    void noOrphanClientSpans(VertxTestContext testContext) throws Exception {
        int requestCount = 3;
        CountDownLatch latch = new CountDownLatch(requestCount);

        for (int i = 0; i < requestCount; i++) {
            webClient.get(port, "localhost", "/api/check" + i)
                    .rxSend()
                    .subscribe(resp -> latch.countDown(), err -> latch.countDown());
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        waitForSpans(requestCount * 2);

        List<SpanData> clientSpans = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.CLIENT)
                .collect(Collectors.toList());

        testContext.verify(() -> {
            assertThat(clientSpans).hasSize(requestCount);

            for (SpanData client : clientSpans) {
                // parentSpanId must NOT be the invalid/empty span id
                assertThat(client.getParentSpanId())
                        .as("CLIENT span '%s' must not be an orphan root span", client.getName())
                        .isNotEqualTo("0000000000000000");

                // CLIENT and its parent SERVER must share the same trace
                assertThat(client.getParentSpanContext().getTraceId())
                        .as("CLIENT span '%s' parent trace must match its own trace", client.getName())
                        .isEqualTo(client.getTraceId());
            }

            testContext.completeNow();
        });

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    private void waitForSpans(int minCount) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (spanExporter.getFinishedSpanItems().size() >= minCount) {
                return;
            }
            Thread.sleep(100);
        }
    }
}
