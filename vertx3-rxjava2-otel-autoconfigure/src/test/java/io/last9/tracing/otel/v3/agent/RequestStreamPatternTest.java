package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.Completable;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.RxHelper;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.http.HttpServerRequest;
import io.last9.tracing.otel.v3.TracedRouter;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the customer's pattern: {@code server.requestStream().toFlowable().doOnNext(router::handle)}
 * instead of {@code server.requestHandler(router)}.
 *
 * <p>Some Vert.x frameworks use this pattern for backpressure support instead
 * of the standard {@code requestHandler(router)} approach.
 */
@ExtendWith(VertxExtension.class)
class RequestStreamPatternTest {

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

        // Use TracedRouter (simulates what RouterAdvice does in production)
        Router router = TracedRouter.create(vertx, otel.getOpenTelemetry());
        router.get("/api/test").handler(ctx ->
                ctx.response().putHeader("content-type", "text/plain").end("ok"));
        router.get("/api/users/:id").handler(ctx ->
                ctx.response().end("user:" + ctx.pathParam("id")));

        // Customer's pattern: requestStream().toFlowable() instead of requestHandler(router)
        var server = vertx.createHttpServer(new HttpServerOptions().setPort(0));
        var handleRequests = server.requestStream()
                .toFlowable()
                .map(HttpServerRequest::pause)
                .onBackpressureDrop(req -> req.response().setStatusCode(503).end())
                .observeOn(RxHelper.scheduler(vertx))
                .doOnNext(req -> router.handle(req))
                .map(HttpServerRequest::resume)
                .doOnError(error -> System.err.println("Error: " + error.getMessage()))
                .ignoreElements();

        server.rxListen()
                .doOnSubscribe(d -> handleRequests.subscribe())
                .subscribe(
                        s -> {
                            port = s.actualPort();
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

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
     * Core reproduction: does the requestStream pattern produce SERVER spans?
     * This is the exact customer scenario that fails in production.
     */
    @Test
    void requestStreamPattern_producesServerSpans(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/test")
                .rxSend()
                .subscribe(
                        resp -> testContext.verify(() -> {
                            assertThat(resp.bodyAsString()).isEqualTo("ok");
                            waitForSpans(1);

                            List<SpanData> serverSpans = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.SERVER)
                                    .collect(Collectors.toList());

                            // THIS IS THE KEY ASSERTION:
                            // If this fails, the requestStream pattern does NOT produce SERVER spans
                            assertThat(serverSpans)
                                    .as("requestStream().toFlowable().doOnNext(router::handle) should produce SERVER spans")
                                    .isNotEmpty();

                            SpanData span = serverSpans.get(0);
                            assertThat(span.getAttributes().get(
                                    AttributeKey.stringKey("http.request.method"))).isEqualTo("GET");
                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Check route pattern naming works with requestStream pattern.
     */
    @Test
    void requestStreamPattern_hasRoutePatternInSpanName(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/users/42")
                .rxSend()
                .subscribe(
                        resp -> testContext.verify(() -> {
                            assertThat(resp.bodyAsString()).isEqualTo("user:42");
                            waitForSpans(1);

                            List<SpanData> serverSpans = spanExporter.getFinishedSpanItems().stream()
                                    .filter(s -> s.getKind() == SpanKind.SERVER)
                                    .collect(Collectors.toList());

                            if (!serverSpans.isEmpty()) {
                                SpanData span = serverSpans.get(0);
                                // Route pattern should show :id, not literal 42
                                assertThat(span.getName()).contains("/api/users");
                            }
                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Same test but with plain Router.router() + manual instrumentExisting()
     * — matches the exact agent code path (RouterAdvice calls instrumentExisting).
     */
    @Test
    void requestStreamPattern_withInstrumentExisting_producesServerSpans(VertxTestContext testContext) throws Exception {
        // Simulate the agent: Router.router(vertx) then instrumentExisting()
        Router plainRouter = Router.router(vertx);
        TracedRouter.instrumentExisting(plainRouter, otel.getOpenTelemetry());

        plainRouter.get("/api/agent-test").handler(ctx ->
                ctx.response().end("agent-ok"));

        // Recreate server with the new router
        var server2 = vertx.createHttpServer(new io.vertx.core.http.HttpServerOptions().setPort(0));
        var handleRequests2 = server2.requestStream()
                .toFlowable()
                .map(HttpServerRequest::pause)
                .onBackpressureDrop(req -> req.response().setStatusCode(503).end())
                .observeOn(RxHelper.scheduler(vertx))
                .doOnNext(req -> plainRouter.handle(req))
                .map(HttpServerRequest::resume)
                .ignoreElements();

        VertxTestContext startCtx = new VertxTestContext();
        server2.rxListen()
                .doOnSubscribe(d -> handleRequests2.subscribe())
                .subscribe(s -> {
                    int port2 = s.actualPort();
                    // Send request to the new server
                    webClient.get(port2, "localhost", "/api/agent-test")
                            .rxSend()
                            .subscribe(
                                    resp -> testContext.verify(() -> {
                                        assertThat(resp.bodyAsString()).isEqualTo("agent-ok");
                                        waitForSpans(1);
                                        List<SpanData> serverSpans = spanExporter.getFinishedSpanItems().stream()
                                                .filter(s2 -> s2.getKind() == SpanKind.SERVER)
                                                .collect(Collectors.toList());
                                        assertThat(serverSpans)
                                                .as("instrumentExisting + requestStream should produce SERVER spans")
                                                .isNotEmpty();
                                        testContext.completeNow();
                                    }),
                                    testContext::failNow
                            );
                }, testContext::failNow);

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Simulates the HttpStreamHandler.handler() advice path — wraps the handler
     * set by requestStream().toFlowable() via HttpServerAdviceHelper.wrapHandler().
     *
     * <p>This is what happens in production when the ByteBuddy agent intercepts
     * HttpStreamHandler.handler(Handler): it calls wrapHandler() on the handler
     * being set. Without TracedRouter or any Router instrumentation, the wrapped
     * handler alone should produce SERVER spans.
     *
     * <p>Uses a plain custom handler (no Router) to prove the HttpStreamHandler
     * intercept works independently.
     */
    @Test
    void requestStreamPattern_withWrappedHandler_producesServerSpans(VertxTestContext testContext) throws Exception {
        // Plain handler — no Router, no TracedRouter
        io.vertx.core.Handler<io.vertx.core.http.HttpServerRequest> plainHandler = request ->
                request.response().putHeader("content-type", "text/plain").end("wrapped-ok");

        // Simulate what the ByteBuddy advice does: wrap the handler
        @SuppressWarnings("unchecked")
        io.vertx.core.Handler<io.vertx.core.http.HttpServerRequest> wrappedHandler =
                (io.vertx.core.Handler<io.vertx.core.http.HttpServerRequest>)
                        HttpServerAdviceHelper.wrapHandler(plainHandler);

        // Use requestStream pattern with the wrapped handler (no Router)
        var server = vertx.getDelegate().createHttpServer(
                new io.vertx.core.http.HttpServerOptions().setPort(0));

        // Manually set the wrapped handler via requestHandler (simulating what
        // happens after HttpStreamHandler.handler() advice wraps the handler)
        server.requestHandler(wrappedHandler);

        VertxTestContext startCtx = new VertxTestContext();
        server.listen(0, ar -> {
            if (ar.succeeded()) {
                int wrappedPort = ar.result().actualPort();
                webClient.get(wrappedPort, "localhost", "/api/wrapped-test")
                        .rxSend()
                        .subscribe(
                                resp -> testContext.verify(() -> {
                                    assertThat(resp.bodyAsString()).isEqualTo("wrapped-ok");
                                    waitForSpans(1);

                                    List<SpanData> serverSpans = spanExporter.getFinishedSpanItems().stream()
                                            .filter(s -> s.getKind() == SpanKind.SERVER)
                                            .collect(Collectors.toList());

                                    assertThat(serverSpans)
                                            .as("wrapHandler() on requestStream handler should produce SERVER spans")
                                            .hasSize(1);

                                    SpanData span = serverSpans.get(0);
                                    assertThat(span.getName()).isEqualTo("GET /api/wrapped-test");
                                    assertThat(span.getAttributes().get(
                                            io.opentelemetry.api.common.AttributeKey.stringKey("http.request.method")))
                                            .isEqualTo("GET");
                                    assertThat(span.getAttributes().get(
                                            io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")))
                                            .isEqualTo(200L);
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
     * Verifies that requestStream().toFlowable() does NOT call requestHandler()
     * on HttpServerImpl — proving the need for the HttpStreamHandler intercept.
     *
     * <p>This test uses a plain Router (no TracedRouter, no wrapHandler) with the
     * requestStream pattern. Without the HttpStreamHandler advice, no SERVER spans
     * should be produced — only the Router's own handling runs.
     */
    @Test
    void requestStreamPattern_withoutInstrumentation_producesNoServerSpans(VertxTestContext testContext) throws Exception {
        // Plain Router — no TracedRouter, no agent instrumentation
        Router uninstrumentedRouter = Router.router(vertx);
        uninstrumentedRouter.get("/api/plain").handler(ctx ->
                ctx.response().end("plain-ok"));

        var server = vertx.createHttpServer(new HttpServerOptions().setPort(0));
        var handleRequests = server.requestStream()
                .toFlowable()
                .map(HttpServerRequest::pause)
                .onBackpressureDrop(req -> req.response().setStatusCode(503).end())
                .observeOn(RxHelper.scheduler(vertx))
                .doOnNext(uninstrumentedRouter::handle)
                .map(HttpServerRequest::resume)
                .ignoreElements();

        VertxTestContext startCtx = new VertxTestContext();
        server.rxListen()
                .doOnSubscribe(d -> handleRequests.subscribe())
                .subscribe(s -> {
                    int plainPort = s.actualPort();
                    webClient.get(plainPort, "localhost", "/api/plain")
                            .rxSend()
                            .subscribe(
                                    resp -> testContext.verify(() -> {
                                        assertThat(resp.bodyAsString()).isEqualTo("plain-ok");
                                        // Wait a bit to be sure no spans arrive
                                        Thread.sleep(500);

                                        List<SpanData> serverSpans = spanExporter.getFinishedSpanItems().stream()
                                                .filter(ss -> ss.getKind() == SpanKind.SERVER)
                                                .collect(Collectors.toList());

                                        assertThat(serverSpans)
                                                .as("Without instrumentation, requestStream pattern should produce ZERO server spans")
                                                .isEmpty();
                                        testContext.completeNow();
                                    }),
                                    testContext::failNow
                            );
                }, testContext::failNow);

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
