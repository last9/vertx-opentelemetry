package io.last9.tracing.otel.v3;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SpanNameUpdater} — both the direct handler method
 * ({@code updateSpanName}) and the router-wide helper ({@code addToAllRoutes}).
 *
 * <p>A span-provider handler at order {@code -2000} starts a SERVER span and makes
 * it current before each route handler runs. This simulates what {@link TracedRouter}
 * does in production, letting us test {@code SpanNameUpdater} in isolation.
 */
@ExtendWith(VertxExtension.class)
class SpanNameUpdaterTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;
    private WebClient webClient;

    /** Server that tests {@link SpanNameUpdater#updateSpanName} used as a handler directly. */
    private int portDirect;

    /** Server that tests {@link SpanNameUpdater#addToAllRoutes} applied to the whole router. */
    private int portAll;

    @BeforeEach
    void setUp(VertxTestContext ctx) throws Exception {
        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();
        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);
        Tracer tracer = otel.getOpenTelemetry().getTracer("test");

        // ---- Server 1: updateSpanName used directly on specific routes ----
        Router routerDirect = Router.router(vertx);
        installSpanProvider(routerDirect, tracer);
        routerDirect.get("/api/users/:id")
                .handler(SpanNameUpdater::updateSpanName)
                .handler(rc -> rc.response().setStatusCode(200).end("ok"));

        // ---- Server 2: addToAllRoutes applied to the whole router ----
        Router routerAll = Router.router(vertx);
        installSpanProvider(routerAll, tracer);
        SpanNameUpdater.addToAllRoutes(routerAll);
        routerAll.get("/api/items/:category").handler(rc -> rc.response().setStatusCode(200).end("items"));
        routerAll.get("/api/error").handler(rc -> rc.response().setStatusCode(500).end("fail"));

        VertxTestContext startCtx1 = new VertxTestContext();
        VertxTestContext startCtx2 = new VertxTestContext();

        vertx.createHttpServer().requestHandler(routerDirect).rxListen(0)
                .subscribe(s -> { portDirect = s.actualPort(); startCtx1.completeNow(); },
                        startCtx1::failNow);
        vertx.createHttpServer().requestHandler(routerAll).rxListen(0)
                .subscribe(s -> { portAll = s.actualPort(); startCtx2.completeNow(); },
                        startCtx2::failNow);

        assertThat(startCtx1.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
        assertThat(startCtx2.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
        ctx.completeNow();
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        otel.shutdown();
    }

    // ---- updateSpanName ----

    @Test
    void updateSpanNameUsesRoutePatternForSpanName(VertxTestContext ctx) throws Exception {
        webClient.get(portDirect, "localhost", "/api/users/42").rxSend()
                .subscribe(resp -> {
                    ctx.verify(() -> {
                        SpanData span = waitForServerSpan();
                        assertThat(span.getName()).isEqualTo("GET /api/users/:id");
                    });
                    ctx.completeNow();
                }, ctx::failNow);
        assertThat(ctx.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void updateSpanNameSetsHttpRouteAttribute(VertxTestContext ctx) throws Exception {
        webClient.get(portDirect, "localhost", "/api/users/99").rxSend()
                .subscribe(resp -> {
                    ctx.verify(() -> {
                        SpanData span = waitForServerSpan();
                        assertThat(span.getAttributes().get(AttributeKey.stringKey("http.route")))
                                .isEqualTo("/api/users/:id");
                    });
                    ctx.completeNow();
                }, ctx::failNow);
        assertThat(ctx.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    // ---- addToAllRoutes ----

    @Test
    void addToAllRoutesUpdatesSpanNameOnResponse(VertxTestContext ctx) throws Exception {
        webClient.get(portAll, "localhost", "/api/items/electronics").rxSend()
                .subscribe(resp -> {
                    ctx.verify(() -> {
                        SpanData span = waitForServerSpan();
                        assertThat(span.getName()).isEqualTo("GET /api/items/:category");
                    });
                    ctx.completeNow();
                }, ctx::failNow);
        assertThat(ctx.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void addToAllRoutesSetsHttpRouteAttribute(VertxTestContext ctx) throws Exception {
        webClient.get(portAll, "localhost", "/api/items/books").rxSend()
                .subscribe(resp -> {
                    ctx.verify(() -> {
                        SpanData span = waitForServerSpan();
                        assertThat(span.getAttributes().get(AttributeKey.stringKey("http.route")))
                                .isEqualTo("/api/items/:category");
                    });
                    ctx.completeNow();
                }, ctx::failNow);
        assertThat(ctx.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void addToAllRoutesSetsHttpResponseStatusCodeAttribute(VertxTestContext ctx) throws Exception {
        webClient.get(portAll, "localhost", "/api/items/tools").rxSend()
                .subscribe(resp -> {
                    ctx.verify(() -> {
                        SpanData span = waitForServerSpan();
                        assertThat(span.getAttributes().get(
                                AttributeKey.longKey("http.response.status_code")))
                                .isEqualTo(200L);
                    });
                    ctx.completeNow();
                }, ctx::failNow);
        assertThat(ctx.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void addToAllRoutesSetsErrorStatusOn5xx(VertxTestContext ctx) throws Exception {
        webClient.get(portAll, "localhost", "/api/error").rxSend()
                .subscribe(resp -> {
                    ctx.verify(() -> {
                        assertThat(resp.statusCode()).isEqualTo(500);
                        SpanData span = waitForServerSpan();
                        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                    });
                    ctx.completeNow();
                }, ctx::failNow);
        assertThat(ctx.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    // ---- Helpers ----

    /**
     * Adds a span-provider handler at order {@code -2000} that creates a SERVER span
     * and makes it current for the duration of the handler chain. This simulates the
     * role {@link TracedRouter} plays in production.
     */
    private static void installSpanProvider(Router router, Tracer tracer) {
        router.route().order(-2000).handler(rc -> {
            Span span = tracer.spanBuilder("http-request")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            rc.response().bodyEndHandler(v -> span.end());
            // Keep scope open while ctx.next() synchronously runs the handler chain.
            // headersEndHandler (fired by response.end()) executes within this scope,
            // so Span.current() and stored span references are both valid.
            try (Scope ignored = span.makeCurrent()) {
                rc.next();
            }
        });
    }

    private SpanData waitForServerSpan() {
        for (int i = 0; i < 100; i++) {
            List<SpanData> spans = spanExporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getKind() == SpanKind.SERVER)
                    .toList();
            if (!spans.isEmpty()) return spans.get(0);
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
        throw new AssertionError("No SERVER span found after 5 seconds");
    }
}
