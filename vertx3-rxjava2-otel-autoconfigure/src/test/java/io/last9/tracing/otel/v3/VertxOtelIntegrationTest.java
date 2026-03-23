package io.last9.tracing.otel.v3;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.Single;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.Schedulers;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
class VertxOtelIntegrationTest {

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

        router.get("/api/test").handler(ctx -> ctx.response().end("ok"));

        router.get("/api/users/:id").handler(ctx -> {
            String userId = ctx.pathParam("id");
            ctx.response().end("user:" + userId);
        });

        router.get("/api/rxjava").handler(ctx -> {
            Single.just("start")
                    .map(v -> {
                        String traceId = Span.current().getSpanContext().getTraceId();
                        return "traced:" + traceId;
                    })
                    .subscribe(
                            result -> ctx.response().putHeader("X-Trace-Result", result).end(result),
                            err -> ctx.response().setStatusCode(500).end(err.getMessage())
                    );
        });

        router.get("/api/rxjava-threaded").handler(ctx -> {
            String requestTraceId = Span.current().getSpanContext().getTraceId();

            Single.just("start")
                    .subscribeOn(Schedulers.io())
                    .map(v -> {
                        String traceId = Span.current().getSpanContext().getTraceId();
                        return traceId.equals(requestTraceId) ? "context-preserved" : "context-lost";
                    })
                    .subscribe(
                            result -> ctx.response().end(result),
                            err -> ctx.response().setStatusCode(500).end(err.getMessage())
                    );
        });

        router.get("/api/error").handler(ctx -> ctx.response().setStatusCode(500).end("error"));

        // POST handler: verifies body is readable via BodyHandler and span is on the context.
        // TracedRouter no longer buffers the body itself to avoid conflicts with apps that
        // register their own BodyHandler (which caused "Empty reply from server").
        router.post("/api/echo")
                .handler(io.vertx.reactivex.ext.web.handler.BodyHandler.create())
                .handler(ctx -> {
                    // Span is stored on the RoutingContext, not in ThreadLocal scope
                    Span span = ctx.get("otel.span");
                    String traceId = span != null ? span.getSpanContext().getTraceId() : "no-span";
                    JsonObject body = ctx.getBodyAsJson();
                    String msg = body != null ? body.getString("msg", "null") : "null";
                    ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(new JsonObject().put("msg", msg).put("traceId", traceId).encode());
                });

        // Downstream receiver: echoes back the traceparent header it received.
        router.get("/api/downstream").handler(ctx -> {
            String traceparent = ctx.request().getHeader("traceparent");
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                            .put("traceparent", traceparent != null ? traceparent : "missing")
                            .encode());
        });

        // Propagation test: calls /api/downstream using ClientTracing.inject() to propagate
        // the current span's trace context into the outgoing request. Uses the two-arg overload
        // so the test SDK (not GlobalOpenTelemetry) resolves the propagator.
        router.get("/api/propagation-test").handler(ctx -> {
            ClientTracing.inject(webClient.get(port, "localhost", "/api/downstream"), otel.getOpenTelemetry())
                    .rxSend()
                    .subscribe(
                            resp -> ctx.response().end(resp.bodyAsString()),
                            err -> ctx.response().setStatusCode(500).end(err.getMessage())
                    );
        });

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

    @Test
    void httpRequestCreatesServerSpan(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/test")
                .rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.bodyAsString()).isEqualTo("ok");
                                waitForSpans(1);
                                SpanData serverSpan = findServerSpan();
                                assertThat(serverSpan.getName()).isEqualTo("GET /api/test");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void routePatternUsedInSpanName(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/users/42")
                .rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.bodyAsString()).isEqualTo("user:42");
                                waitForSpans(1);
                                SpanData serverSpan = findServerSpan();
                                assertThat(serverSpan.getName()).isEqualTo("GET /api/users/:id");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rxJavaChainPreservesTraceContext(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/rxjava")
                .rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                String result = resp.bodyAsString();
                                assertThat(result).startsWith("traced:");
                                String traceId = result.substring("traced:".length());
                                assertThat(traceId).matches("[0-9a-f]{32}");
                                assertThat(traceId).isNotEqualTo("00000000000000000000000000000000");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rxJavaThreadedChainPreservesTraceContext(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/rxjava-threaded")
                .rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.bodyAsString()).isEqualTo("context-preserved");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void multipleRequestsCreateSeparateTraces(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/test")
                .rxSend()
                .flatMap(resp1 -> webClient.get(port, "localhost", "/api/test").rxSend())
                .subscribe(
                        resp2 -> {
                            testContext.verify(() -> {
                                waitForSpans(2);
                                List<SpanData> serverSpans = spanExporter.getFinishedSpanItems().stream()
                                        .filter(s -> s.getKind() == SpanKind.SERVER)
                                        .toList();
                                assertThat(serverSpans).hasSizeGreaterThanOrEqualTo(2);
                                assertThat(serverSpans.get(0).getTraceId())
                                        .isNotEqualTo(serverSpans.get(1).getTraceId());
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void errorResponseSetsSpanErrorStatus(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/error")
                .rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                waitForSpans(1);
                                SpanData serverSpan = findServerSpan();
                                assertThat(serverSpan.getStatus().getStatusCode())
                                        .isEqualTo(StatusCode.ERROR);
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void postHandlerBodyReadableAndSpanOnContext(VertxTestContext testContext) throws Exception {
        // Verifies that POST body is readable via BodyHandler and the span is accessible
        // on the RoutingContext. TracedRouter no longer buffers the body itself — apps
        // must use BodyHandler. The span is stored on ctx as "otel.span".
        JsonObject requestBody = new JsonObject().put("msg", "hello");

        webClient.post(port, "localhost", "/api/echo")
                .putHeader("content-type", "application/json")
                .rxSendJsonObject(requestBody)
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                JsonObject responseBody = resp.bodyAsJsonObject();
                                // Body was readable via BodyHandler + ctx.getBodyAsJson()
                                assertThat(responseBody.getString("msg")).isEqualTo("hello");
                                // Span is accessible from RoutingContext (trace_id is non-zero)
                                String traceId = responseBody.getString("traceId");
                                assertThat(traceId).matches("[0-9a-f]{32}");
                                assertThat(traceId).isNotEqualTo("00000000000000000000000000000000");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Simulates the actual agent behavior: CoreRouterAdvice fires on the core Router,
     * then RouterAdvice fires on the RxJava Router but SKIPS because the core Router
     * is already in INSTRUMENTED. Only the core-level tracing handler is active.
     * Tests that SERVER spans still work when the app uses RxJava handlers.
     */
    @Test
    void coreOnlyInstrumentationWithRxJavaHandlers(VertxTestContext testContext) throws Exception {
        // Create a plain RxJava Router (NOT via TracedRouter.create)
        Router router = Router.router(vertx);

        // Only instrument the core Router — simulates what happens when
        // CoreRouterAdvice fires first and TracedRouter.instrumentExisting()
        // returns early because core Router is already in INSTRUMENTED.
        CoreTracedRouter.instrumentExisting(router.getDelegate(), otel.getOpenTelemetry());

        // Add a simple handler (like the customer's AbstractRoute pattern)
        router.get("/api/agent-test").handler(ctx ->
                ctx.response().end("agent-ok"));

        CountDownLatch serverReady = new CountDownLatch(1);
        int[] testPort = new int[1];
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, ar -> {
                    testPort[0] = ar.result().actualPort();
                    serverReady.countDown();
                });
        serverReady.await(5, TimeUnit.SECONDS);

        webClient.get(testPort[0], "localhost", "/api/agent-test")
                .rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.statusCode()).isEqualTo(200);
                                assertThat(resp.bodyAsString()).isEqualTo("agent-ok");

                                // Wait for span export
                                Thread.sleep(200);
                                List<SpanData> serverSpans = spanExporter.getFinishedSpanItems().stream()
                                        .filter(s -> s.getKind() == SpanKind.SERVER)
                                        .collect(java.util.stream.Collectors.toList());

                                // This MUST produce a SERVER span
                                assertThat(serverSpans).as("SERVER span should be exported").hasSize(1);
                                assertThat(serverSpans.get(0).getName()).contains("GET");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void clientTracingInjectsTraceparentHeader(VertxTestContext testContext) throws Exception {
        // Regression test for Bug 2: outgoing WebClient calls in Vert.x 3 don't automatically
        // carry traceparent (no client SPI). ClientTracing.inject() uses the OTel propagator to
        // write the current span's context into the request headers, linking the downstream span
        // to the same trace.
        webClient.get(port, "localhost", "/api/propagation-test")
                .rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                JsonObject body = resp.bodyAsJsonObject();
                                String traceparent = body.getString("traceparent");
                                // The downstream service received a valid traceparent header
                                assertThat(traceparent).isNotEqualTo("missing");
                                assertThat(traceparent).startsWith("00-");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    // ---- Helpers ----

    private SpanData findServerSpan() {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.SERVER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SERVER span found"));
    }

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
