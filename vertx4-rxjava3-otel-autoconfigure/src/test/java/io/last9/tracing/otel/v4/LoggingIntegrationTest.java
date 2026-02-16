package io.last9.tracing.otel.v4;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
class LoggingIntegrationTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;
    private int port;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger testLogger;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        RxJavaPlugins.reset();
        resetInstalledFlag();
        MDC.clear();

        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();

        testLogger = (Logger) LoggerFactory.getLogger("test.logging");
        listAppender = new ListAppender<>();
        listAppender.start();
        testLogger.addAppender(listAppender);

        VertxOptions vertxOptions = new VertxOptions()
                .setTracingOptions(new OpenTelemetryOptions(otel.getOpenTelemetry()));
        vertx = Vertx.vertx(vertxOptions);

        RxJava3ContextPropagation.install();

        Router router = TracedRouter.create(vertx);

        router.get("/api/log-test").handler(ctx -> {
            testLogger.info("Processing request for log test");
            ctx.response().end("logged");
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
        if (testLogger != null) {
            testLogger.detachAppender(listAppender);
        }
        listAppender.stop();
        RxJavaPlugins.reset();
        MDC.clear();
        if (vertx != null) {
            vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        }
        otel.shutdown();
    }

    @Test
    void logsContainTraceIdDuringTracedRequest(VertxTestContext testContext) throws Exception {
        vertx.createHttpClient()
                .rxRequest(HttpMethod.GET, port, "localhost", "/api/log-test")
                .flatMap(req -> req.rxSend())
                .flatMap(resp -> resp.rxBody())
                .subscribe(
                        body -> {
                            testContext.verify(() -> {
                                assertThat(body.toString()).isEqualTo("logged");

                                List<ILoggingEvent> logEvents = listAppender.list;
                                assertThat(logEvents).isNotEmpty();

                                ILoggingEvent logEvent = logEvents.stream()
                                        .filter(e -> e.getMessage().contains("Processing request for log test"))
                                        .findFirst()
                                        .orElseThrow(() -> new AssertionError("Log event not found"));

                                String traceId = logEvent.getMDCPropertyMap().get("trace_id");
                                String spanId = logEvent.getMDCPropertyMap().get("span_id");

                                assertThat(traceId)
                                        .as("trace_id should be present in log MDC")
                                        .isNotNull()
                                        .matches("[0-9a-f]{32}");
                                assertThat(spanId)
                                        .as("span_id should be present in log MDC")
                                        .isNotNull()
                                        .matches("[0-9a-f]{16}");

                                // Verify trace_id matches the actual server span
                                waitForSpans(1);
                                List<SpanData> spans = spanExporter.getFinishedSpanItems();
                                SpanData serverSpan = spans.stream()
                                        .filter(s -> s.getKind() == SpanKind.SERVER)
                                        .findFirst()
                                        .orElse(null);
                                if (serverSpan != null) {
                                    assertThat(traceId)
                                            .as("Log trace_id should match the server span's trace_id")
                                            .isEqualTo(serverSpan.getTraceId());
                                }
                            });
                            testContext.completeNow();
                        },
                        testContext::failNow
                );

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void logsOutsideSpanDoNotHaveTraceId() {
        testLogger.info("Outside any span");

        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).isNotEmpty();

        ILoggingEvent logEvent = logEvents.get(logEvents.size() - 1);
        assertThat(logEvent.getMDCPropertyMap().get("trace_id"))
                .as("trace_id should not be present outside a span")
                .isNull();
    }

    private void waitForSpans(int minCount) {
        for (int i = 0; i < 50; i++) {
            if (spanExporter.getFinishedSpanItems().size() >= minCount) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
    }

    private void resetInstalledFlag() {
        try {
            var field = RxJava3ContextPropagation.class.getDeclaredField("installed");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean) field.get(null)).set(false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset installed flag", e);
        }
    }
}
