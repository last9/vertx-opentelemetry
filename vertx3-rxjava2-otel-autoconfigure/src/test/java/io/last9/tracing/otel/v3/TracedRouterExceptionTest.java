package io.last9.tracing.otel.v3;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.plugins.RxJavaPlugins;
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
 * Tests exception recording on SERVER spans produced by {@link TracedRouter}.
 *
 * <p>TracedRouter's {@code headersEndHandler} fires when the response is sent.
 * It records exceptions on the span when <em>both</em> conditions hold:
 * <ol>
 *   <li>HTTP response status is {@code >= 500}</li>
 *   <li>{@code ctx.failure()} is non-null — meaning the handler called
 *       {@code ctx.fail(error)} or {@code ctx.fail(status, error)}</li>
 * </ol>
 *
 * <p>When a handler sends {@code ctx.response().setStatusCode(500).end()} directly,
 * {@code ctx.failure()} stays null and no exception event is recorded, even though
 * the span's status is set to ERROR. This is the key difference between the two
 * 500-response paths and is tested explicitly here.
 */
@ExtendWith(VertxExtension.class)
class TracedRouterExceptionTest {

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

        // ctx.fail(throwable) — failure() is set, TracedRouter calls span.recordException()
        router.get("/api/fail-throwable").handler(ctx ->
                ctx.fail(new RuntimeException("boom")));

        // ctx.fail(500, throwable) — explicit 5xx status + failure() set
        router.get("/api/fail-with-status").handler(ctx ->
                ctx.fail(500, new IllegalArgumentException("bad input")));

        // Direct setStatusCode(500) — failure() stays null, no exception event
        router.get("/api/direct-500").handler(ctx ->
                ctx.response().setStatusCode(500).end("error"));

        // ctx.fail(400, throwable) — 4xx, should NOT set ERROR status or record exception
        router.get("/api/fail-4xx").handler(ctx ->
                ctx.fail(400, new IllegalArgumentException("client error")));

        // ArithmeticException — verifies exception.type uses fully-qualified class name
        router.get("/api/fail-arithmetic").handler(ctx ->
                ctx.fail(new ArithmeticException("/ by zero")));

        // NullPointerException — getMessage() returns null; stacktrace should still appear
        router.get("/api/fail-npe").handler(ctx -> {
            String s = null;
            //noinspection ConstantConditions
            s.length(); // deliberate NPE — handler never reaches ctx.fail, exception propagates
        });

        // Failure handler required for ctx.fail() routes to actually send a response.
        // Without this Vert.x would return a default HTML error page.
        // TracedRouter's headersEndHandler fires when this handler sends the response.
        router.route().failureHandler(ctx -> {
            int status = ctx.statusCode() > 0 ? ctx.statusCode() : 500;
            String msg = ctx.failure() != null ? ctx.failure().getMessage() : "error";
            ctx.response().setStatusCode(status).end(msg != null ? msg : "error");
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

    // ---- ctx.fail(error) path ----

    @Test
    void ctxFailSetsErrorStatusOnSpan(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/fail-throwable").rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.statusCode()).isEqualTo(500);
                                SpanData span = waitForServerSpan();
                                assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void ctxFailRecordsExceptionEventOnSpan(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/fail-throwable").rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                SpanData span = waitForServerSpan();
                                assertThat(span.getEvents())
                                        .as("Exception event must be recorded when ctx.fail(error) is called")
                                        .anyMatch(e -> e.getName().equals("exception"));
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void ctxFailExceptionEventHasTypeAndMessage(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/fail-throwable").rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                SpanData span = waitForServerSpan();
                                EventData event = findExceptionEvent(span);

                                assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.type")))
                                        .isEqualTo("java.lang.RuntimeException");
                                assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.message")))
                                        .isEqualTo("boom");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void ctxFailExceptionEventHasStacktrace(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/fail-throwable").rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                SpanData span = waitForServerSpan();
                                EventData event = findExceptionEvent(span);

                                String stacktrace = event.getAttributes()
                                        .get(AttributeKey.stringKey("exception.stacktrace"));
                                assertThat(stacktrace)
                                        .as("exception.stacktrace must be non-blank and mention the exception class")
                                        .isNotBlank()
                                        .contains("RuntimeException")
                                        .contains("boom");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void ctxFailExceptionEventHasEscapedTrue(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/fail-throwable").rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                SpanData span = waitForServerSpan();
                                EventData event = findExceptionEvent(span);

                                assertThat(event.getAttributes().get(AttributeKey.booleanKey("exception.escaped")))
                                        .as("exception.escaped must be true for exceptions propagated via ctx.fail()")
                                        .isTrue();
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    // ---- ctx.fail(status, error) path ----

    @Test
    void ctxFailWithExplicitStatusRecordsExceptionEvent(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/fail-with-status").rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.statusCode()).isEqualTo(500);
                                SpanData span = waitForServerSpan();
                                assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);

                                EventData event = findExceptionEvent(span);
                                assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.type")))
                                        .isEqualTo("java.lang.IllegalArgumentException");
                                assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.message")))
                                        .isEqualTo("bad input");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    // ---- Exception type fidelity ----

    @Test
    void arithmeticExceptionTypeIsFullyQualified(VertxTestContext testContext) throws Exception {
        webClient.get(port, "localhost", "/api/fail-arithmetic").rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                SpanData span = waitForServerSpan();
                                EventData event = findExceptionEvent(span);
                                assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.type")))
                                        .isEqualTo("java.lang.ArithmeticException");
                                assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.message")))
                                        .isEqualTo("/ by zero");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void nullPointerExceptionStacktraceIsRecordedEvenWithNullMessage(VertxTestContext testContext)
            throws Exception {
        webClient.get(port, "localhost", "/api/fail-npe").rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.statusCode()).isEqualTo(500);
                                SpanData span = waitForServerSpan();
                                EventData event = findExceptionEvent(span);

                                // NPE message is null — exception.message attribute may be absent or null
                                assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.type")))
                                        .isEqualTo("java.lang.NullPointerException");
                                // Stacktrace must still be present even when message is null
                                assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.stacktrace")))
                                        .isNotBlank()
                                        .contains("NullPointerException");
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    // ---- Negative cases ----

    @Test
    void directStatusCode500WithoutCtxFailHasNoExceptionEvent(VertxTestContext testContext)
            throws Exception {
        // When a handler sends the response directly without ctx.fail(), ctx.failure() stays null.
        // TracedRouter still sets ERROR status (5xx), but cannot record an exception event.
        webClient.get(port, "localhost", "/api/direct-500").rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.statusCode()).isEqualTo(500);
                                SpanData span = waitForServerSpan();

                                assertThat(span.getStatus().getStatusCode())
                                        .as("5xx status must still mark span as ERROR")
                                        .isEqualTo(StatusCode.ERROR);
                                assertThat(span.getEvents())
                                        .as("No exception event because ctx.failure() was null")
                                        .noneMatch(e -> e.getName().equals("exception"));
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void clientError4xxDoesNotSetErrorStatusOrExceptionEvent(VertxTestContext testContext)
            throws Exception {
        // Per OTel HTTP semantic conventions, 4xx client errors must NOT set span status to ERROR
        // and must NOT record an exception event on the server span.
        webClient.get(port, "localhost", "/api/fail-4xx").rxSend()
                .subscribe(
                        resp -> {
                            testContext.verify(() -> {
                                assertThat(resp.statusCode()).isEqualTo(400);
                                SpanData span = waitForServerSpan();

                                assertThat(span.getStatus().getStatusCode())
                                        .as("4xx must not set span status to ERROR")
                                        .isNotEqualTo(StatusCode.ERROR);
                                assertThat(span.getEvents())
                                        .as("4xx must not produce an exception event")
                                        .noneMatch(e -> e.getName().equals("exception"));
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );
        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    // ---- Helpers ----

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

    private EventData findExceptionEvent(SpanData span) {
        return span.getEvents().stream()
                .filter(e -> e.getName().equals("exception"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected exception event on span '" + span.getName()
                                + "' but found none. Events: " + span.getEvents()));
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
