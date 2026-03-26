package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link NettyServerTracingHandler} and its deduplication with
 * {@link HttpServerAdviceHelper}.
 *
 * <p>Since ByteBuddy is not active in unit tests, we test:
 * <ul>
 *   <li>Normal flow: HttpServerAdviceHelper creates SERVER spans when no Netty span exists</li>
 *   <li>Guard logic: maybeInject ignores non-HttpServerCodec handlers</li>
 *   <li>Deduplication: HttpServerAdviceHelper adopts a pre-existing Netty span</li>
 *   <li>Bytecode: handler does not reference SemanticAttributes (classpath safety)</li>
 * </ul>
 *
 * <p>The dedup test uses a custom requestHandler that sets the ThreadLocal BEFORE
 * delegating to the wrapped handler — simulating what the Netty pipeline handler
 * does in production (channelRead sets ThreadLocal → handler.handle fires in same
 * call stack).
 */
@ExtendWith(VertxExtension.class)
class NettyServerTracingHandlerTest {

    private GlobalOtelTestSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;
    private WebClient webClient;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        HttpServerAdviceHelper.resetForTest();
        NettyServerTracingHandler.NETTY_SERVER_SPAN.remove();
        otel = new GlobalOtelTestSetup();
        otel.setUp();
        spanExporter = otel.getSpanExporter();
        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);
    }

    @AfterEach
    void tearDown() {
        NettyServerTracingHandler.NETTY_SERVER_SPAN.remove();
        if (vertx != null) {
            vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        }
        otel.tearDown();
    }

    /**
     * Start an HTTP server with the given request handler.
     */
    private void startServer(Handler<HttpServerRequest> handler, VertxTestContext testContext)
            throws Exception {
        vertx.getDelegate().createHttpServer()
                .requestHandler(handler)
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

    /**
     * Without Netty span: HttpServerAdviceHelper creates its own SERVER span
     * with full attributes (method, path, status code).
     */
    @Test
    void withoutNettySpan_adviceHelperCreatesOwnSpan(VertxTestContext testContext) throws Exception {
        Handler<HttpServerRequest> handler = request ->
                request.response().putHeader("content-type", "text/plain").end("ok");

        @SuppressWarnings("unchecked")
        Handler<HttpServerRequest> wrapped =
                (Handler<HttpServerRequest>) HttpServerAdviceHelper.wrapHandler(handler);

        VertxTestContext startCtx = new VertxTestContext();
        startServer(wrapped, startCtx);

        webClient.get(port, "localhost", "/api/test")
                .rxSend()
                .subscribe(
                        resp -> testContext.verify(() -> {
                            assertThat(resp.bodyAsString()).isEqualTo("ok");
                            waitForSpans(1);

                            List<SpanData> serverSpans = getServerSpans();
                            assertThat(serverSpans).hasSize(1);

                            SpanData span = serverSpans.get(0);
                            assertThat(span.getName()).isEqualTo("GET /api/test");
                            assertThat(span.getAttributes().get(
                                    AttributeKey.stringKey("http.request.method"))).isEqualTo("GET");
                            assertThat(span.getAttributes().get(
                                    AttributeKey.stringKey("url.path"))).isEqualTo("/api/test");
                            assertThat(span.getAttributes().get(
                                    AttributeKey.longKey("http.response.status_code"))).isEqualTo(200L);
                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * With Netty span: simulates the Netty pipeline handler creating a span
     * BEFORE the Vert.x handler fires (same call stack in production).
     *
     * <p>We wrap the request handler with a shim that sets the ThreadLocal
     * before delegating to the HttpServerAdviceHelper-wrapped handler. This
     * reproduces the production call stack: channelRead → NETTY_SERVER_SPAN.set()
     * → handler.handle(request).
     *
     * <p>Expects: exactly 1 SERVER span (the Netty one, adopted by the helper),
     * NOT 2 (which would indicate the helper created a duplicate).
     */
    @Test
    void withNettySpan_adviceHelperAdoptsIt(VertxTestContext testContext) throws Exception {
        Handler<HttpServerRequest> inner = request ->
                request.response().putHeader("content-type", "text/plain").end("ok");

        @SuppressWarnings("unchecked")
        Handler<HttpServerRequest> adviceWrapped =
                (Handler<HttpServerRequest>) HttpServerAdviceHelper.wrapHandler(inner);

        // Shim that simulates the Netty handler setting the ThreadLocal
        // in channelRead, before the Vert.x handler fires.
        Handler<HttpServerRequest> nettySimulator = request -> {
            Span nettySpan = GlobalOpenTelemetry.getTracer("io.last9.tracing.otel.v3")
                    .spanBuilder(request.method().name() + " " + request.path())
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.request.method", request.method().name())
                    .setAttribute("url.path", request.path())
                    .startSpan();

            NettyServerTracingHandler.NETTY_SERVER_SPAN.set(nettySpan);
            try {
                adviceWrapped.handle(request);
            } finally {
                // In production, NettyServerTracingHandler ends the span in write().
                // Here we end it after the handler returns.
                nettySpan.end();
            }
        };

        VertxTestContext startCtx = new VertxTestContext();
        startServer(nettySimulator, startCtx);

        webClient.get(port, "localhost", "/api/test")
                .rxSend()
                .subscribe(
                        resp -> testContext.verify(() -> {
                            assertThat(resp.bodyAsString()).isEqualTo("ok");
                            waitForSpans(1);

                            List<SpanData> serverSpans = getServerSpans();

                            // Exactly 1 SERVER span — the Netty one. Helper adopted it.
                            assertThat(serverSpans).hasSize(1);
                            SpanData span = serverSpans.get(0);
                            assertThat(span.getName()).contains("GET /api/test");
                            assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * After adoption, the ThreadLocal is cleared (no leak to next request).
     */
    @Test
    void nettySpanThreadLocal_clearedAfterAdoption(VertxTestContext testContext) throws Exception {
        Handler<HttpServerRequest> inner = request ->
                request.response().end("ok");

        @SuppressWarnings("unchecked")
        Handler<HttpServerRequest> adviceWrapped =
                (Handler<HttpServerRequest>) HttpServerAdviceHelper.wrapHandler(inner);

        Handler<HttpServerRequest> nettySimulator = request -> {
            Span nettySpan = GlobalOpenTelemetry.getTracer("io.last9.tracing.otel.v3")
                    .spanBuilder("GET /test")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            NettyServerTracingHandler.NETTY_SERVER_SPAN.set(nettySpan);
            try {
                adviceWrapped.handle(request);
            } finally {
                // ThreadLocal should already be cleared by the helper
                assertThat(NettyServerTracingHandler.NETTY_SERVER_SPAN.get())
                        .as("ThreadLocal should be cleared after adoption").isNull();
                nettySpan.end();
            }
        };

        VertxTestContext startCtx = new VertxTestContext();
        startServer(nettySimulator, startCtx);

        webClient.get(port, "localhost", "/api/test")
                .rxSend()
                .subscribe(
                        resp -> testContext.verify(() -> {
                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * maybeInject ignores non-HttpServerCodec handlers (no exception).
     */
    @Test
    void maybeInject_ignoresNonCodecHandler() {
        NettyServerTracingHandler.maybeInject("not a codec", "not a pipeline");
        // No exception
    }

    /**
     * maybeInject ignores null arguments safely.
     */
    @Test
    void maybeInject_handlesNullSafely() {
        NettyServerTracingHandler.maybeInject(null, null);
        // No exception
    }

    /**
     * Bytecode does not reference SemanticAttributes (classpath safety).
     */
    @Test
    void handlerDoesNotReferenceSemanticAttributesClass() throws Exception {
        String className = NettyServerTracingHandler.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (java.io.InputStream is = NettyServerTracingHandler.class.getClassLoader()
                .getResourceAsStream(className)) {
            assertThat(is).as("NettyServerTracingHandler.class should be loadable").isNotNull();
            bytecode = is.readAllBytes();
        }

        String bytecodeAsString = new String(bytecode, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(bytecodeAsString)
                .as("NettyServerTracingHandler must not reference SemanticAttributes")
                .doesNotContain("SemanticAttributes")
                .doesNotContain("ExceptionAttributes")
                .doesNotContain("io/opentelemetry/semconv");
    }

    private List<SpanData> getServerSpans() {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.SERVER)
                .collect(Collectors.toList());
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
