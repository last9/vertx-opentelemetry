package io.last9.tracing.otel.v3;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.context.Scope;
import io.reactivex.plugins.RxJavaPlugins;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.client.WebClient;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the correct pattern for POST/PUT routes that read the request body.
 *
 * <h2>Root cause of the AbstractReqResRoute / 502 bug</h2>
 * <p>TracedRouter does not buffer the request body. Routes that call
 * {@code ctx.getBodyAsJson()} synchronously (like a framework wrapping
 * {@code AbstractReqResRoute}) receive {@code null} and throw an NPE that is
 * never routed to {@code ctx.fail()} — leaving the request unanswered, which a
 * load balancer surfaces as a 502.
 *
 * <h2>Correct pattern</h2>
 * <ol>
 *   <li>Add route-level {@code BodyHandler.create()} to the route.</li>
 *   <li>Retrieve the span via {@code ctx.get("otel.span")} rather than
 *       {@code Span.current()} — {@code BodyHandler} calls {@code ctx.next()}
 *       asynchronously so the OTel thread-local scope is already closed by the
 *       time the route handler runs.</li>
 *   <li>To make outgoing calls (or subscribe RxJava chains) with the correct
 *       trace context, re-activate the span with
 *       {@code span.makeCurrent()}.</li>
 * </ol>
 */
@ExtendWith(VertxExtension.class)
class TracedRouterBodyBufferingTest {

    private TestOtelSetup otel;
    private Vertx vertx;
    private WebClient webClient;
    private int port;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        RxJavaPlugins.reset();
        TestOtelSetup.resetRxJava2InstalledFlag();

        otel = new TestOtelSetup();
        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);
        RxJava2ContextPropagation.install();

        Router router = TracedRouter.create(vertx, otel.getOpenTelemetry());

        // Correct pattern: route-level BodyHandler + ctx.get("otel.span").
        // This is how AbstractReqResRoute-style routes should be set up.
        router.post("/api/data")
                .handler(BodyHandler.create())
                .handler(ctx -> {
                    JsonObject body = ctx.getBodyAsJson();
                    if (body == null) {
                        ctx.response().setStatusCode(400).end("no-body");
                        return;
                    }
                    ctx.response().end(body.getString("key", "missing"));
                });

        // GET route (no body) — span is current via Span.current() as usual.
        router.get("/api/query").handler(ctx ->
                ctx.response().end(ctx.queryParam("q").stream().findFirst().orElse("empty")));

        // POST route that also verifies span is accessible and trace context is propagatable.
        router.post("/api/span-check")
                .handler(BodyHandler.create())
                .handler(ctx -> {
                    // Span is stored in RoutingContext — retrieve it directly.
                    Span span = ctx.get("otel.span");
                    String traceId = (span != null)
                            ? span.getSpanContext().getTraceId()
                            : "no-span";

                    // Re-activate span to prove it can be used for outgoing calls.
                    String activatedTraceId = "not-activated";
                    if (span != null) {
                        try (Scope ignored = span.makeCurrent()) {
                            activatedTraceId = Span.current().getSpanContext().getTraceId();
                        }
                    }

                    JsonObject reqBody = ctx.getBodyAsJson();
                    String key = reqBody != null ? reqBody.getString("k", "missing") : "null";

                    ctx.response().putHeader("content-type", "application/json")
                            .end(new JsonObject()
                                    .put("key", key)
                                    .put("traceId", traceId)
                                    .put("activatedTraceId", activatedTraceId)
                                    .encode());
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

    /**
     * Core regression: POST body is available when route-level BodyHandler is used.
     */
    @Test
    void postRouteCanReadRequestBodyWithRouteBodyHandler(VertxTestContext testContext) throws Exception {
        webClient.post(port, "localhost", "/api/data")
                .putHeader("content-type", "application/json")
                .rxSendJsonObject(new JsonObject().put("key", "hello"))
                .subscribe(
                        resp -> testContext.verify(() -> {
                            assertThat(resp.statusCode())
                                    .as("Route handler should receive the body — 400 means body was null")
                                    .isEqualTo(200);
                            assertThat(resp.bodyAsString()).isEqualTo("hello");

                            SpanData span = otel.waitForServerSpan();
                            assertThat(span.getName()).isEqualTo("POST /api/data");
                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * GET route (no body) continues to work with Span.current() as before.
     */
    @Test
    void getRouteWithQueryParamStillWorks(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/query?q=world")
                .rxSend()
                .subscribe(
                        resp -> testContext.verify(() -> {
                            assertThat(resp.statusCode()).isEqualTo(200);
                            assertThat(resp.bodyAsString()).isEqualTo("world");
                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Span is accessible via ctx.get("otel.span") in a POST handler that uses BodyHandler,
     * and can be re-activated with span.makeCurrent() for outgoing call propagation.
     */
    @Test
    void spanIsAccessibleViaContextAndCanBeReactivated(VertxTestContext testContext) throws Exception {
        webClient.post(port, "localhost", "/api/span-check")
                .putHeader("content-type", "application/json")
                .rxSendJsonObject(new JsonObject().put("k", "ok"))
                .subscribe(
                        resp -> testContext.verify(() -> {
                            assertThat(resp.statusCode()).isEqualTo(200);
                            JsonObject body = resp.bodyAsJsonObject();

                            assertThat(body.getString("key")).isEqualTo("ok");

                            String traceId = body.getString("traceId");
                            assertThat(traceId)
                                    .as("span must be on the RoutingContext")
                                    .isNotEqualTo("no-span")
                                    .matches("[0-9a-f]{32}")
                                    .isNotEqualTo("00000000000000000000000000000000");

                            assertThat(body.getString("activatedTraceId"))
                                    .as("span.makeCurrent() must re-activate the trace context")
                                    .isEqualTo(traceId);

                            testContext.completeNow();
                        }),
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }
}
