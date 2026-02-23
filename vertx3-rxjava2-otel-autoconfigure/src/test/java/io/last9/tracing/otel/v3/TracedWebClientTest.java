package io.last9.tracing.otel.v3;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

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
        // Downstream server that echoes back the traceparent header it receives
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

        // Create a TracedWebClient with our test OTel instance
        WebClient traced = TracedWebClient.wrap(WebClient.create(vertx), otel.getOpenTelemetry());

        // Create a parent span and make a request from within it
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
                            // traceparent should contain the parent span's trace ID
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
    void tracedWebClientInjectsTraceparentOnPostAbs(Vertx vertx, VertxTestContext testCtx) throws Exception {
        Router router = Router.router(vertx);
        router.post("/api/receive").handler(ctx -> {
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
                .spanBuilder("test-post")
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            traced.postAbs("http://localhost:" + port + "/api/receive")
                    .rxSend()
                    .subscribe(response -> {
                        testCtx.verify(() -> {
                            String body = response.bodyAsString();
                            assertThat(body).startsWith("00-");
                            assertThat(body).contains(parentSpan.getSpanContext().getTraceId());
                        });
                        testCtx.completeNow();
                    }, testCtx::failNow);
        } finally {
            parentSpan.end();
        }

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void noTraceparentWhenNoActiveSpan(Vertx vertx, VertxTestContext testCtx) throws Exception {
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

        // No active span — traceparent should not be injected
        traced.get(port, "localhost", "/api/check")
                .rxSend()
                .subscribe(response -> {
                    testCtx.verify(() -> {
                        assertThat(response.bodyAsString()).isEqualTo("none");
                    });
                    testCtx.completeNow();
                }, testCtx::failNow);

        assertThat(testCtx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }
}
