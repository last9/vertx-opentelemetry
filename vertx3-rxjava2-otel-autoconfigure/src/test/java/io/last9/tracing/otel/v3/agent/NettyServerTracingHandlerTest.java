package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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
 */
@ExtendWith(VertxExtension.class)
class NettyServerTracingHandlerTest {

    private GlobalOtelTestSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;
    private WebClient webClient;

    @BeforeEach
    void setUp() throws Exception {
        HttpServerAdviceHelper.resetForTest();
        otel = new GlobalOtelTestSetup();
        otel.setUp();
        spanExporter = otel.getSpanExporter();
        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        }
        otel.tearDown();
    }

    /**
     * When no span exists in context, HttpServerAdviceHelper creates its own.
     */
    @Test
    void withoutExistingSpan_adviceHelperCreatesOwnSpan(VertxTestContext testContext) throws Exception {
        Handler<HttpServerRequest> handler = request ->
                request.response().putHeader("content-type", "text/plain").end("ok");

        @SuppressWarnings("unchecked")
        Handler<HttpServerRequest> wrapped =
                (Handler<HttpServerRequest>) HttpServerAdviceHelper.wrapHandler(handler);

        VertxTestContext startCtx = new VertxTestContext();
        vertx.getDelegate().createHttpServer()
                .requestHandler(wrapped)
                .listen(0, ar -> {
                    if (ar.succeeded()) {
                        int port = ar.result().actualPort();
                        webClient.get(port, "localhost", "/api/test")
                                .rxSend()
                                .subscribe(
                                        resp -> testContext.verify(() -> {
                                            assertThat(resp.bodyAsString()).isEqualTo("ok");
                                            waitForSpans(1);
                                            List<SpanData> serverSpans = getServerSpans();
                                            assertThat(serverSpans).hasSize(1);
                                            assertThat(serverSpans.get(0).getName()).isEqualTo("GET /api/test");
                                            testContext.completeNow();
                                        }),
                                        testContext::failNow
                                );
                    } else {
                        testContext.failNow(ar.cause());
                    }
                });

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * When a SERVER span already exists in the OTel context (e.g., from Netty handler),
     * HttpServerAdviceHelper adopts it instead of creating a duplicate.
     */
    @Test
    void withExistingSpan_adviceHelperAdoptsIt(VertxTestContext testContext) throws Exception {
        Handler<HttpServerRequest> inner = request ->
                request.response().putHeader("content-type", "text/plain").end("ok");

        @SuppressWarnings("unchecked")
        Handler<HttpServerRequest> adviceWrapped =
                (Handler<HttpServerRequest>) HttpServerAdviceHelper.wrapHandler(inner);

        // Shim that simulates the Netty RequestHandler making a span current
        Handler<HttpServerRequest> nettySimulator = request -> {
            Span nettySpan = GlobalOpenTelemetry.getTracer("io.last9.tracing.otel.v3")
                    .spanBuilder(request.method().name() + " " + request.path())
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.request.method", request.method().name())
                    .startSpan();

            // Make span current — dedup check uses Span.current()
            Context otelContext = Context.root().with(nettySpan);
            try (Scope ignored = otelContext.makeCurrent()) {
                adviceWrapped.handle(request);
            } finally {
                nettySpan.end();
            }
        };

        VertxTestContext startCtx = new VertxTestContext();
        vertx.getDelegate().createHttpServer()
                .requestHandler(nettySimulator)
                .listen(0, ar -> {
                    if (ar.succeeded()) {
                        int port = ar.result().actualPort();
                        webClient.get(port, "localhost", "/api/test")
                                .rxSend()
                                .subscribe(
                                        resp -> testContext.verify(() -> {
                                            assertThat(resp.bodyAsString()).isEqualTo("ok");
                                            waitForSpans(1);
                                            List<SpanData> serverSpans = getServerSpans();
                                            // Exactly 1 — the Netty span. Helper adopted it.
                                            assertThat(serverSpans).hasSize(1);
                                            testContext.completeNow();
                                        }),
                                        testContext::failNow
                                );
                    } else {
                        testContext.failNow(ar.cause());
                    }
                });

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * maybeInject ignores non-HTTP-codec handlers.
     */
    @Test
    void maybeInject_ignoresNonCodecHandler() {
        NettyServerTracingHandler.maybeInject("not a codec", "not a pipeline");
    }

    /**
     * maybeInject handles null safely.
     */
    @Test
    void maybeInject_handlesNullSafely() {
        NettyServerTracingHandler.maybeInject(null, null);
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
                .doesNotContain("SemanticAttributes")
                .doesNotContain("io/opentelemetry/semconv");
    }

    /**
     * Stores full OTel Context (not just Span) for baggage propagation.
     */
    @Test
    void storesFullContextNotJustSpan() throws Exception {
        // The handler should store Context, not Span, in channel attributes.
        // Verified by checking the class references Context in its fields.
        String className = NettyServerTracingHandler.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (java.io.InputStream is = NettyServerTracingHandler.class.getClassLoader()
                .getResourceAsStream(className)) {
            assertThat(is).isNotNull();
            bytecode = is.readAllBytes();
        }
        String bytecodeAsString = new String(bytecode, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(bytecodeAsString)
                .as("Should store full Context for baggage propagation")
                .contains("server.context");
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
