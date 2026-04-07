package io.last9.tracing.otel.v3;

import io.last9.tracing.otel.v3.agent.NettyHttpClientHelper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.SemanticAttributes;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.client.HttpRequest;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
class TracedWebClientTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void setUp() {
        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();
    }

    @AfterEach
    void tearDown() {
        otel.shutdown();
    }

    @Test
    void tracedWebClientInjectsTraceparentOnGet(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.get("/api/echo-headers").handler(ctx -> {
            String traceparent = ctx.request().getHeader("traceparent");
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("traceparent", traceparent).encode());
        });

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        WebClient traced = TracedWebClient.wrap(WebClient.create(vertx), otel.getOpenTelemetry());

        Span parentSpan = otel.getOpenTelemetry()
                .getTracer("test")
                .spanBuilder("test-parent")
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            traced.get(port, "localhost", "/api/echo-headers")
                    .rxSend()
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            JsonObject body = response.bodyAsJsonObject();
                            String traceparent = body.getString("traceparent");

                            assertThat(traceparent).isNotNull();
                            assertThat(traceparent).startsWith("00-");
                            assertThat(traceparent).contains(parentSpan.getSpanContext().getTraceId());
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        } finally {
            parentSpan.end();
        }

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void tracedWebClientCreatesClientSpan(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.get("/api/data").handler(ctx ->
                ctx.response().setStatusCode(200).end("ok"));

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        WebClient traced = TracedWebClient.wrap(WebClient.create(vertx), otel.getOpenTelemetry());

        Span parentSpan = otel.getOpenTelemetry()
                .getTracer("test")
                .spanBuilder("test-parent")
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            traced.getAbs("http://localhost:" + port + "/api/data")
                    .rxSend()
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            assertThat(response.statusCode()).isEqualTo(200);

                            waitForSpans(1);
                            List<SpanData> clientSpans = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                                    .collect(Collectors.toList());

                            assertThat(clientSpans).hasSize(1);
                            SpanData clientSpan = clientSpans.get(0);

                            // Verify OTel HTTP client semantic convention attributes
                            assertThat(clientSpan.getName()).isEqualTo("GET");
                            assertThat(clientSpan.getAttributes().get(SemanticAttributes.HTTP_REQUEST_METHOD))
                                    .isEqualTo("GET");
                            assertThat(clientSpan.getAttributes().get(SemanticAttributes.URL_FULL))
                                    .isEqualTo("http://localhost:" + port + "/api/data");
                            assertThat(clientSpan.getAttributes().get(SemanticAttributes.SERVER_ADDRESS))
                                    .isEqualTo("localhost");
                            assertThat(clientSpan.getAttributes().get(SemanticAttributes.SERVER_PORT))
                                    .isEqualTo((long) port);
                            assertThat(clientSpan.getAttributes().get(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE))
                                    .isEqualTo(200L);

                            // Verify parent-child relationship
                            assertThat(clientSpan.getTraceId())
                                    .isEqualTo(parentSpan.getSpanContext().getTraceId());
                            assertThat(clientSpan.getParentSpanId())
                                    .isEqualTo(parentSpan.getSpanContext().getSpanId());
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        } finally {
            parentSpan.end();
        }

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void clientSpanInjectsItsOwnContextNotParent(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.get("/api/check-parent").handler(ctx -> {
            String traceparent = ctx.request().getHeader("traceparent");
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("traceparent", traceparent).encode());
        });

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        WebClient traced = TracedWebClient.wrap(WebClient.create(vertx), otel.getOpenTelemetry());

        Span parentSpan = otel.getOpenTelemetry()
                .getTracer("test")
                .spanBuilder("test-parent")
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            traced.getAbs("http://localhost:" + port + "/api/check-parent")
                    .rxSend()
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            String traceparent = response.bodyAsJsonObject().getString("traceparent");

                            waitForSpans(1);
                            SpanData clientSpan = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError("No CLIENT span found"));

                            // The traceparent received by downstream should contain the CLIENT
                            // span's ID (not the parent span's ID)
                            assertThat(traceparent).contains(clientSpan.getSpanId());
                            assertThat(traceparent).doesNotContain(parentSpan.getSpanContext().getSpanId());
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        } finally {
            parentSpan.end();
        }

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void clientSpanRecordsErrorOnServerError(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.get("/api/fail").handler(ctx ->
                ctx.response().setStatusCode(500).end("internal error"));

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        WebClient traced = TracedWebClient.wrap(WebClient.create(vertx), otel.getOpenTelemetry());

        Span parentSpan = otel.getOpenTelemetry()
                .getTracer("test")
                .spanBuilder("test-parent")
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            traced.getAbs("http://localhost:" + port + "/api/fail")
                    .rxSend()
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            assertThat(response.statusCode()).isEqualTo(500);

                            waitForSpans(1);
                            SpanData clientSpan = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError("No CLIENT span found"));

                            assertThat(clientSpan.getAttributes().get(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE))
                                    .isEqualTo(500L);
                            assertThat(clientSpan.getStatus().getStatusCode())
                                    .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        } finally {
            parentSpan.end();
        }

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void postAbsCreatesClientSpanWithCorrectMethod(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.post("/api/receive").handler(ctx -> {
            ctx.response().setStatusCode(201).end("created");
        });

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        WebClient traced = TracedWebClient.wrap(WebClient.create(vertx), otel.getOpenTelemetry());

        Span parentSpan = otel.getOpenTelemetry()
                .getTracer("test")
                .spanBuilder("test-post")
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            traced.postAbs("http://localhost:" + port + "/api/receive")
                    .rxSend()
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            waitForSpans(1);
                            SpanData clientSpan = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError("No CLIENT span found"));

                            assertThat(clientSpan.getName()).isEqualTo("POST");
                            assertThat(clientSpan.getAttributes().get(SemanticAttributes.HTTP_REQUEST_METHOD))
                                    .isEqualTo("POST");
                            assertThat(clientSpan.getAttributes().get(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE))
                                    .isEqualTo(201L);
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        } finally {
            parentSpan.end();
        }

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void noClientSpanWhenNoActiveSpan(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.get("/api/check").handler(ctx -> {
            String traceparent = ctx.request().getHeader("traceparent");
            ctx.response().end(traceparent != null ? traceparent : "none");
        });

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        WebClient traced = TracedWebClient.wrap(WebClient.create(vertx), otel.getOpenTelemetry());

        // No active span — CLIENT span should still be created (as a root span)
        // but traceparent should still be injected from it
        traced.get(port, "localhost", "/api/check")
                .rxSend()
                .subscribe(response -> {
                    testCtx.verify(() -> {
                        waitForSpans(1);
                        List<SpanData> clientSpans = spanExporter.getFinishedSpanItems().stream()
                                .filter(s -> s.getKind() == SpanKind.CLIENT)
                                .collect(Collectors.toList());

                        // A CLIENT span is still created even with no explicit parent
                        assertThat(clientSpans).hasSize(1);
                        SpanData clientSpan = clientSpans.get(0);

                        // traceparent should be injected from the CLIENT span
                        String body = response.bodyAsString();
                        assertThat(body).startsWith("00-");
                        assertThat(body).contains(clientSpan.getSpanId());
                    });
                    testCtx.completeNow();
                }, testCtx::failNow);

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Simulates a customer who has subclassed WebClient to add custom headers.
     */
    static class CustomWebClient extends WebClient {
        private final String customHeaderValue;

        CustomWebClient(Vertx vertx, String customHeaderValue) {
            super(WebClient.create(vertx).getDelegate());
            this.customHeaderValue = customHeaderValue;
        }

        @Override
        public HttpRequest<Buffer> get(int port, String host, String requestURI) {
            return super.get(port, host, requestURI)
                    .putHeader("X-Custom-Auth", customHeaderValue);
        }

        @Override
        public HttpRequest<Buffer> getAbs(String absoluteURI) {
            return super.getAbs(absoluteURI)
                    .putHeader("X-Custom-Auth", customHeaderValue);
        }
    }

    @Test
    void wrapPreservesCustomWebClientBehavior(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.get("/api/echo").handler(ctx -> {
            String traceparent = ctx.request().getHeader("traceparent");
            String customAuth = ctx.request().getHeader("X-Custom-Auth");
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                            .put("traceparent", traceparent)
                            .put("customAuth", customAuth)
                            .encode());
        });

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        CustomWebClient custom = new CustomWebClient(vertx, "Bearer secret-token");
        WebClient traced = TracedWebClient.wrap(custom, otel.getOpenTelemetry());

        Span parentSpan = otel.getOpenTelemetry()
                .getTracer("test")
                .spanBuilder("test-custom-client")
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            traced.get(port, "localhost", "/api/echo")
                    .rxSend()
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            JsonObject body = response.bodyAsJsonObject();

                            // traceparent injected by TracedWebClient
                            assertThat(body.getString("traceparent")).isNotNull();
                            assertThat(body.getString("traceparent")).startsWith("00-");

                            // custom header preserved from customer's subclass
                            assertThat(body.getString("customAuth"))
                                    .isEqualTo("Bearer secret-token");

                            // CLIENT span should be present
                            waitForSpans(1);
                            assertThat(spanExporter.getFinishedSpanItems().stream()
                                    .anyMatch(s -> s.getKind() == SpanKind.CLIENT)).isTrue();
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        } finally {
            parentSpan.end();
        }

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rawMethodCreatesClientSpan(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.route("/api/raw").handler(ctx -> {
            String traceparent = ctx.request().getHeader("traceparent");
            ctx.response().end(traceparent != null ? traceparent : "missing");
        });

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        WebClient traced = TracedWebClient.wrap(WebClient.create(vertx), otel.getOpenTelemetry());

        Span parentSpan = otel.getOpenTelemetry()
                .getTracer("test")
                .spanBuilder("test-raw")
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            traced.raw("GET", port, "localhost", "/api/raw")
                    .rxSend()
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            String body = response.bodyAsString();
                            assertThat(body).startsWith("00-");

                            waitForSpans(1);
                            SpanData clientSpan = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError("No CLIENT span found"));

                            assertThat(clientSpan.getName()).isEqualTo("GET");
                            assertThat(body).contains(clientSpan.getSpanId());
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        } finally {
            parentSpan.end();
        }

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * POST with a JSON body calls rxSendJsonObject() which internally serializes to a Buffer
     * and calls end(Buffer chunk) — not the no-arg end(). This is the core regression test
     * for the bug where POST/PUT CLIENT spans were silently missing.
     */
    @Test
    void postWithJsonBodyCreatesClientSpan(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.post("/api/submit").handler(ctx ->
                ctx.response().setStatusCode(201).end("created"));

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        WebClient traced = TracedWebClient.wrap(WebClient.create(vertx), otel.getOpenTelemetry());

        Span parentSpan = otel.getOpenTelemetry()
                .getTracer("test")
                .spanBuilder("test-post-body")
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            traced.postAbs("http://localhost:" + port + "/api/submit")
                    .rxSendJsonObject(new JsonObject().put("key", "value"))
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            assertThat(response.statusCode()).isEqualTo(201);

                            waitForSpans(1);
                            List<SpanData> clientSpans = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                                    .collect(Collectors.toList());

                            assertThat(clientSpans).hasSize(1);
                            SpanData clientSpan = clientSpans.get(0);
                            assertThat(clientSpan.getName()).isEqualTo("POST");
                            assertThat(clientSpan.getAttributes()
                                    .get(SemanticAttributes.HTTP_REQUEST_METHOD)).isEqualTo("POST");
                            assertThat(clientSpan.getAttributes()
                                    .get(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE)).isEqualTo(201L);
                            // Must be child of the parent span
                            assertThat(clientSpan.getParentSpanId())
                                    .isEqualTo(parentSpan.getSpanContext().getSpanId());
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        } finally {
            parentSpan.end();
        }

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * PUT with a Buffer body calls rxSendBuffer() which also calls end(Buffer chunk).
     * Ensures PUT requests with body produce CLIENT spans.
     */
    @Test
    void putWithBufferBodyCreatesClientSpan(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.put("/api/update").handler(ctx ->
                ctx.response().setStatusCode(200).end("updated"));

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        WebClient traced = TracedWebClient.wrap(WebClient.create(vertx), otel.getOpenTelemetry());

        Span parentSpan = otel.getOpenTelemetry()
                .getTracer("test")
                .spanBuilder("test-put-buffer")
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            traced.putAbs("http://localhost:" + port + "/api/update")
                    .rxSendBuffer(Buffer.buffer("payload"))
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            assertThat(response.statusCode()).isEqualTo(200);

                            waitForSpans(1);
                            List<SpanData> clientSpans = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.CLIENT)
                                    .collect(Collectors.toList());

                            assertThat(clientSpans).hasSize(1);
                            SpanData clientSpan = clientSpans.get(0);
                            assertThat(clientSpan.getName()).isEqualTo("PUT");
                            assertThat(clientSpan.getAttributes()
                                    .get(SemanticAttributes.HTTP_REQUEST_METHOD)).isEqualTo("PUT");
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        } finally {
            parentSpan.end();
        }

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Regression test: IN_HTTP_CLIENT_CALL must be TRUE at the moment the inner delegate's
     * rxSend() is executed (which is when Vert.x calls end() to dispatch the HTTP request).
     * This is the precise moment NettyHttpClientAdvice.onEnter fires.
     *
     * Bug: the old code used try/finally around sendFn.get() to set/reset the flag. But
     * Single.defer subscribes to the returned Single AFTER the finally block completes, so
     * the flag was always false by the time end() fired — causing duplicate CLIENT spans.
     *
     * Fix: use doOnSubscribe to set the flag right before the source is subscribed to.
     *
     * Threading note: must subscribe from the Vert.x event loop (via runOnContext) so that
     * doOnSubscribe (sets flag) and doOnSuccess (clears flag) run on the same thread as the
     * actual HTTP send. IN_HTTP_CLIENT_CALL is a ThreadLocal — cross-thread use is undefined.
     */
    @Test
    void inHttpClientCallGuardIsTrueDuringDelegateSubscription(Vertx vertx, VertxTestContext testCtx)
            throws Exception {
        AtomicBoolean guardWasTrueAtSendTime = new AtomicBoolean(false);

        Router router = Router.router(vertx);
        router.get("/api/guard-check").handler(ctx ->
                ctx.response().setStatusCode(200).end("ok"));

        int port = vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .blockingGet()
                .actualPort();

        // A WebClient whose rxSend() wraps the real request in Single.create so the spy
        // lambda runs AFTER onSubscribe propagation (i.e., after doOnSubscribe(set_flag_true)
        // has fired) but BEFORE the actual HTTP send — exactly where NettyHttpClientAdvice fires.
        WebClient spyClient = new WebClient(WebClient.create(vertx).getDelegate()) {
            @Override
            public HttpRequest<Buffer> getAbs(String absoluteURI) {
                HttpRequest<Buffer> real = super.getAbs(absoluteURI);
                return new HttpRequest<Buffer>(real.getDelegate()) {
                    @Override
                    @SuppressWarnings("unchecked")
                    public io.reactivex.Single<io.vertx.reactivex.ext.web.client.HttpResponse<Buffer>> rxSend() {
                        return io.reactivex.Single.create(emitter -> {
                            // Single.create's lambda runs INSIDE subscribeActual, AFTER
                            // onSubscribe has propagated outward (so doOnSubscribe(set_flag_true)
                            // has already fired). This is equivalent to when end() would be called.
                            guardWasTrueAtSendTime.set(NettyHttpClientHelper.IN_HTTP_CLIENT_CALL.get());
                            real.rxSend().subscribe(
                                    resp -> emitter.onSuccess((io.vertx.reactivex.ext.web.client.HttpResponse<Buffer>) resp),
                                    emitter::tryOnError
                            );
                        });
                    }
                };
            }
        };

        WebClient traced = TracedWebClient.wrap(spyClient, otel.getOpenTelemetry());

        // Run the subscribe from the Vert.x event loop so all ThreadLocal reads/writes
        // (doOnSubscribe, the send, doOnSuccess) happen on the same thread.
        vertx.runOnContext(ignored -> {
            assertThat(NettyHttpClientHelper.IN_HTTP_CLIENT_CALL.get()).isFalse();

            traced.getAbs("http://localhost:" + port + "/api/guard-check")
                    .rxSend()
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            // Guard must have been TRUE when the send lambda ran.
                            // If false, NettyHttpClientAdvice would have created a duplicate span.
                            assertThat(guardWasTrueAtSendTime.get())
                                    .as("IN_HTTP_CLIENT_CALL must be true when send() is called")
                                    .isTrue();

                            // Guard must be reset to false after the request completes.
                            assertThat(NettyHttpClientHelper.IN_HTTP_CLIENT_CALL.get()).isFalse();
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        });

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    // ---- Helpers ----

    private void waitForSpans(int minCount) {
        for (int i = 0; i < 50; i++) {
            if (spanExporter.getFinishedSpanItems().size() >= minCount) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
    }
}
