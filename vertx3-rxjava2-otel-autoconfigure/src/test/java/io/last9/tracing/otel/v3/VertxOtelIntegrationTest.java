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
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
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
