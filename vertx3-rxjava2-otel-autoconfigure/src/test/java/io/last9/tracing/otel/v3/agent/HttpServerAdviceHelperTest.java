package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
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
 * Tests for {@link HttpServerAdviceHelper} — verifies that SERVER spans are created
 * for apps that use a custom requestHandler instead of a Vert.x Router.
 *
 * <p>This reproduces the exact customer scenario where apps use a custom dispatcher
 * (e.g., AbstractApplication) that wraps the Router, causing the Router advice to
 * fire but not produce SERVER spans.
 */
@ExtendWith(VertxExtension.class)
class HttpServerAdviceHelperTest {

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

        // Simulate a custom request handler that does NOT use Router.
        // Apps with custom frameworks use httpServer.requestHandler(customDispatcher)
        // instead of httpServer.requestHandler(router)
        Handler<HttpServerRequest> customDispatcher = request -> {
            // Custom dispatch logic — no Router involved
            if ("/api/hello".equals(request.path())) {
                request.response().putHeader("content-type", "text/plain").end("hello");
            } else if ("/api/users/42".equals(request.path())) {
                request.response().putHeader("content-type", "text/plain").end("user:42");
            } else {
                request.response().setStatusCode(404).end("not found");
            }
        };

        // Wrap the handler just like HttpServerAdvice would do via ByteBuddy
        @SuppressWarnings("unchecked")
        Handler<HttpServerRequest> wrappedHandler =
                (Handler<HttpServerRequest>) HttpServerAdviceHelper.wrapHandler(customDispatcher);

        vertx.getDelegate().createHttpServer()
                .requestHandler(wrappedHandler)
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

    @Test
    void customHandlerProducesServerSpan(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/hello")
                .rxSend()
                .subscribe(
                        resp -> testContext.verify(() -> {
                            assertThat(resp.bodyAsString()).isEqualTo("hello");
                            waitForSpans(1);

                            List<SpanData> serverSpans = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.SERVER)
                                    .collect(Collectors.toList());

                            assertThat(serverSpans).hasSize(1);
                            SpanData span = serverSpans.get(0);
                            assertThat(span.getName()).isEqualTo("GET /api/hello");
                            assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void customHandlerSetsHttpAttributes(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/users/42")
                .rxSend()
                .subscribe(
                        resp -> testContext.verify(() -> {
                            assertThat(resp.bodyAsString()).isEqualTo("user:42");
                            waitForSpans(1);

                            SpanData span = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.SERVER)
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError("No SERVER span found"));

                            assertThat(span.getName()).isEqualTo("GET /api/users/42");
                            // Verify attributes use string keys (not SemanticAttributes constants)
                            // to avoid classpath conflicts when apps bundle older OTel semconv
                            assertThat(span.getAttributes().get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey("http.request.method")))
                                    .isEqualTo("GET");
                            assertThat(span.getAttributes().get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey("url.path")))
                                    .isEqualTo("/api/users/42");
                            assertThat(span.getAttributes().get(
                                    io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")))
                                    .isEqualTo(200L);
                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void customHandler404SetsStatusCode(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/nonexistent")
                .rxSend()
                .subscribe(
                        resp -> testContext.verify(() -> {
                            assertThat(resp.statusCode()).isEqualTo(404);
                            waitForSpans(1);

                            SpanData span = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.SERVER)
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError("No SERVER span found"));

                            assertThat(span.getAttributes().get(
                                    io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")))
                                    .isEqualTo(404L);
                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void wrapHandlerIsIdempotent() {
        Handler<HttpServerRequest> handler = request -> request.response().end("ok");
        Object wrapped1 = HttpServerAdviceHelper.wrapHandler(handler);
        Object wrapped2 = HttpServerAdviceHelper.wrapHandler(handler);

        // Second wrap should return the original handler (already tracked)
        assertThat(wrapped1).isNotSameAs(handler);   // first wrap creates new handler
        assertThat(wrapped2).isSameAs(handler);       // second call returns original (tracked)
    }

    @Test
    void wrapHandlerHandlesNull() {
        assertThat(HttpServerAdviceHelper.wrapHandler(null)).isNull();
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
