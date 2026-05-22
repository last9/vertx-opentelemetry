package io.last9.tracing.otel.v4;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link SpanNameUpdater#addToAllRoutes(Router)} exception recording on SERVER spans.
 */
@ExtendWith(VertxExtension.class)
class SpanNameUpdaterTest {

    private TestOtelSetup otel;
    private Vertx vertx;
    private WebClient webClient;
    private int port;

    @BeforeEach
    void setUp(VertxTestContext ctx) throws Exception {
        otel = new TestOtelSetup();
        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);
        Tracer tracer = otel.getTracer();

        Router router = Router.router(vertx);
        installSpanProvider(router, tracer);
        SpanNameUpdater.addToAllRoutes(router);

        router.get("/api/items/:id").handler(rc -> rc.response().setStatusCode(200).end("ok"));
        router.get("/api/fail").handler(rc -> rc.fail(new RuntimeException("boom")));
        router.get("/api/direct-500").handler(rc -> rc.response().setStatusCode(500).end("error"));
        router.route().failureHandler(rc -> {
            int status = rc.statusCode() > 0 ? rc.statusCode() : 500;
            String msg = rc.failure() != null ? rc.failure().getMessage() : "error";
            rc.response().setStatusCode(status).end(msg != null ? msg : "error");
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .subscribe(
                        server -> {
                            port = server.actualPort();
                            ctx.completeNow();
                        },
                        ctx::failNow);

        assertThat(ctx.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        }
        otel.shutdown();
    }

    @Test
    void addToAllRoutesRecordsExceptionOnCtxFail(VertxTestContext ctx) throws Exception {
        webClient.get(port, "localhost", "/api/fail").rxSend()
                .subscribe(resp -> {
                    ctx.verify(() -> {
                        assertThat(resp.statusCode()).isEqualTo(500);
                        SpanData span = otel.waitForServerSpan();
                        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
                    });
                    ctx.completeNow();
                }, ctx::failNow);
        assertThat(ctx.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void addToAllRoutesRecordsStacktraceOnCtxFail(VertxTestContext ctx) throws Exception {
        webClient.get(port, "localhost", "/api/fail").rxSend()
                .subscribe(resp -> {
                    ctx.verify(() -> {
                        EventData event = findExceptionEvent(otel.waitForServerSpan());
                        assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.stacktrace")))
                                .isNotBlank()
                                .contains("RuntimeException")
                                .contains("boom");
                    });
                    ctx.completeNow();
                }, ctx::failNow);
        assertThat(ctx.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void addToAllRoutesDirect500WithoutCtxFailHasNoExceptionEvent(VertxTestContext ctx) throws Exception {
        webClient.get(port, "localhost", "/api/direct-500").rxSend()
                .subscribe(resp -> {
                    ctx.verify(() -> {
                        assertThat(resp.statusCode()).isEqualTo(500);
                        SpanData span = otel.waitForServerSpan();
                        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                        assertThat(span.getEvents()).noneMatch(e -> e.getName().equals("exception"));
                    });
                    ctx.completeNow();
                }, ctx::failNow);
        assertThat(ctx.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    private static EventData findExceptionEvent(SpanData span) {
        return span.getEvents().stream()
                .filter(e -> e.getName().equals("exception"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No exception event on span: " + span.getName()));
    }

    private static void installSpanProvider(Router router, Tracer tracer) {
        router.route().order(-2000).handler(rc -> {
            Span span = tracer.spanBuilder("http-request")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            rc.response().bodyEndHandler(v -> span.end());
            try (Scope ignored = span.makeCurrent()) {
                rc.next();
            }
        });
    }
}
