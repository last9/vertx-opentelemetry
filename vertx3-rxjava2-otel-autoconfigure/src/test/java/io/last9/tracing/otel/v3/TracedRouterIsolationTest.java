package io.last9.tracing.otel.v3;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.Single;
import io.reactivex.plugins.RxJavaPlugins;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that TracedRouter creates isolated traces for independent HTTP requests.
 *
 * <p>These tests verify the fix for a scope-leak bug where {@code Context.current()} was
 * used as the base context in {@code propagator.extract()}. On Vert.x's single-threaded
 * event loop, a leaked scope from a prior request would cause subsequent requests to
 * nest under the previous request's SERVER span, producing one giant cascading trace
 * instead of independent traces per request.
 *
 * <p>The fix uses {@code Context.root()} so each request starts with a clean context.
 */
@ExtendWith(VertxExtension.class)
class TracedRouterIsolationTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;
    private WebClient webClient;
    private int port;

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

        router.get("/api/fast").handler(ctx ->
                ctx.response().end("fast"));

        // Simulates an async handler (DB call, HTTP call) that takes time
        router.get("/api/slow").handler(ctx ->
                vertx.setTimer(50, id -> ctx.response().end("slow")));

        // Handler that does an RxJava chain (common in real apps)
        router.get("/api/rx").handler(ctx ->
                Single.just("rx-result")
                        .delay(10, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .subscribe(
                                result -> ctx.response().end(result),
                                err -> ctx.response().setStatusCode(500).end()
                        ));

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
        RxJavaPlugins.reset();
        if (vertx != null) {
            vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        }
        otel.shutdown();
    }

    /**
     * Core regression test: sequential requests without traceparent must produce
     * independent traces. Before the fix, the second request's SERVER span would
     * parent under the first request's SERVER span because Context.current() on
     * the event loop thread still held the first request's context.
     */
    @Test
    void sequentialRequestsProduceIndependentTraces(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/fast")
                .rxSend()
                .flatMap(r1 -> webClient.get(port, "localhost", "/api/fast").rxSend())
                .flatMap(r2 -> webClient.get(port, "localhost", "/api/fast").rxSend())
                .subscribe(
                        r3 -> {
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
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Verifies that no SERVER span has another SERVER span as its parent.
     * In a correctly isolated setup, SERVER spans from independent requests
     * should either be root spans or have an external parent (from traceparent).
     */
    @Test
    void serverSpansDoNotNestUnderEachOther(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/fast")
                .rxSend()
                .flatMap(r1 -> webClient.get(port, "localhost", "/api/slow").rxSend())
                .flatMap(r2 -> webClient.get(port, "localhost", "/api/fast").rxSend())
                .subscribe(
                        r3 -> {
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
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Tests that async handlers (timer-based) don't leak context to the next request.
     * The slow handler uses vertx.setTimer() which defers the response — this was
     * a common trigger for the scope leak.
     */
    @Test
    void asyncHandlerDoesNotLeakContextToNextRequest(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/slow")
                .rxSend()
                .flatMap(r1 -> webClient.get(port, "localhost", "/api/fast").rxSend())
                .subscribe(
                        r2 -> {
                            testContext.verify(() -> {
                                waitForSpans(2);
                                List<SpanData> serverSpans = getServerSpans();
                                assertThat(serverSpans).hasSize(2);

                                assertThat(serverSpans.get(0).getTraceId())
                                        .as("Slow and fast requests should have different traces")
                                        .isNotEqualTo(serverSpans.get(1).getTraceId());
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Tests that RxJava-based handlers don't leak context to the next request.
     * RxJava context propagation hooks can interact with the OTel thread-local.
     */
    @Test
    void rxHandlerDoesNotLeakContextToNextRequest(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/rx")
                .rxSend()
                .flatMap(r1 -> webClient.get(port, "localhost", "/api/fast").rxSend())
                .subscribe(
                        r2 -> {
                            testContext.verify(() -> {
                                waitForSpans(2);
                                List<SpanData> serverSpans = getServerSpans();
                                assertThat(serverSpans).hasSize(2);

                                assertThat(serverSpans.get(0).getTraceId())
                                        .as("RxJava and fast requests should have different traces")
                                        .isNotEqualTo(serverSpans.get(1).getTraceId());
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * When a request includes a valid traceparent header, the SERVER span should
     * honour it. But the NEXT request (without traceparent) must NOT inherit that
     * foreign trace context.
     */
    @Test
    void traceparentHonouredButNotLeakedToNextRequest(VertxTestContext testContext) throws Exception {
        String foreignTraceId = "0af7651916cd43dd8448eb211c80319c";
        String foreignParentId = "b7ad6b7169203331";
        String traceparent = "00-" + foreignTraceId + "-" + foreignParentId + "-01";

        webClient.get(port, "localhost", "/api/fast")
                .putHeader("traceparent", traceparent)
                .rxSend()
                .flatMap(r1 -> webClient.get(port, "localhost", "/api/fast").rxSend())
                .subscribe(
                        r2 -> {
                            testContext.verify(() -> {
                                waitForSpans(2);
                                List<SpanData> serverSpans = getServerSpans();
                                assertThat(serverSpans).hasSize(2);

                                // Find the span that honoured the traceparent
                                SpanData tracedSpan = serverSpans.stream()
                                        .filter(s -> s.getTraceId().equals(foreignTraceId))
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError(
                                                "Expected a span with the foreign trace ID"));
                                assertThat(tracedSpan.getParentSpanContext().getSpanId())
                                        .isEqualTo(foreignParentId);

                                // The next request must NOT have the foreign trace ID
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
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Sends many requests in quick succession to stress-test trace isolation.
     * Every SERVER span must have a unique trace ID.
     */
    @Test
    void manySequentialRequestsAllHaveUniqueTraces(VertxTestContext testContext) throws Exception {
        int count = 10;

        // Chain 10 requests sequentially using flatMap
        Single<?> chain = webClient.get(port, "localhost", "/api/fast").rxSend();
        for (int i = 1; i < count; i++) {
            chain = chain.flatMap(r -> webClient.get(port, "localhost", "/api/fast").rxSend());
        }

        chain.subscribe(
                v -> {
                    testContext.verify(() -> {
                        waitForSpans(count);
                        List<SpanData> serverSpans = getServerSpans();
                        assertThat(serverSpans).hasSize(count);

                        Set<String> traceIds = serverSpans.stream()
                                .map(SpanData::getTraceId)
                                .collect(Collectors.toSet());
                        assertThat(traceIds)
                                .as("All %d requests should have unique trace IDs", count)
                                .hasSize(count);
                    });
                    testContext.completeNow();
                },
                testContext::failNow
        );

        assertThat(testContext.awaitCompletion(30, TimeUnit.SECONDS)).isTrue();
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
