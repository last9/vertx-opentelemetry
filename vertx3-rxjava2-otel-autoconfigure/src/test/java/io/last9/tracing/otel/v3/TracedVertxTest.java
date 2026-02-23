package io.last9.tracing.otel.v3;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link TracedVertx#rxExecuteBlocking(Vertx, io.vertx.core.Handler)}
 * correctly propagates OTel context to worker threads.
 */
@ExtendWith(VertxExtension.class)
class TracedVertxTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;

    @BeforeEach
    void setUp() {
        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        otel.shutdown();
        if (vertx != null) {
            vertx.rxClose().blockingAwait(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void propagatesOtelContextToWorkerThread(VertxTestContext testContext) throws Exception {
        Span parentSpan = otel.getTracer().spanBuilder("test-parent")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        AtomicReference<String> workerTraceId = new AtomicReference<>();
        AtomicReference<String> workerParentSpanId = new AtomicReference<>();

        try (Scope ignored = parentSpan.makeCurrent()) {
            TracedVertx.<String>rxExecuteBlocking(vertx, promise -> {
                // On the worker thread, Span.current() should be the parent span
                Span current = Span.current();
                workerTraceId.set(current.getSpanContext().getTraceId());
                workerParentSpanId.set(current.getSpanContext().getSpanId());
                promise.complete("done");
            }).subscribe(
                    result -> {
                        testContext.verify(() -> {
                            // The worker thread should see the same trace context
                            assertThat(workerTraceId.get())
                                    .as("Worker thread should have the parent's trace ID")
                                    .isEqualTo(parentSpan.getSpanContext().getTraceId());
                            assertThat(workerParentSpanId.get())
                                    .as("Worker thread should have the parent's span ID")
                                    .isEqualTo(parentSpan.getSpanContext().getSpanId());
                        });
                        testContext.completeNow();
                    },
                    testContext::failNow
            );
        } finally {
            parentSpan.end();
        }

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void childSpanOnWorkerParentsUnderEventLoopSpan(VertxTestContext testContext) throws Exception {
        Span parentSpan = otel.getTracer().spanBuilder("server-request")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            TracedVertx.<String>rxExecuteBlocking(vertx, promise -> {
                // Create a child span on the worker thread (simulates TracedAerospikeClient)
                Span childSpan = otel.getTracer().spanBuilder("aerospike GET")
                        .setSpanKind(SpanKind.CLIENT)
                        .startSpan();
                childSpan.end();
                promise.complete("done");
            }).subscribe(
                    result -> {
                        parentSpan.end();
                        testContext.verify(() -> {
                            List<SpanData> spans = spanExporter.getFinishedSpanItems();
                            assertThat(spans).hasSize(2);

                            SpanData serverSpan = spans.stream()
                                    .filter(s -> s.getName().equals("server-request"))
                                    .findFirst().orElseThrow();
                            SpanData clientSpan = spans.stream()
                                    .filter(s -> s.getName().equals("aerospike GET"))
                                    .findFirst().orElseThrow();

                            // Child span on worker thread should parent under the server span
                            assertThat(clientSpan.getTraceId())
                                    .isEqualTo(serverSpan.getTraceId());
                            assertThat(clientSpan.getParentSpanContext().getSpanId())
                                    .isEqualTo(serverSpan.getSpanContext().getSpanId());
                        });
                        testContext.completeNow();
                    },
                    testContext::failNow
            );
        }

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void withoutTracedVertxWorkerHasNoContext(VertxTestContext testContext) throws Exception {
        Span parentSpan = otel.getTracer().spanBuilder("server-request")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        AtomicReference<Boolean> workerHasContext = new AtomicReference<>();

        try (Scope ignored = parentSpan.makeCurrent()) {
            // Use plain rxExecuteBlocking (NOT TracedVertx) — worker should have NO context
            vertx.<String>rxExecuteBlocking(promise -> {
                workerHasContext.set(Span.current().getSpanContext().isValid());
                promise.complete("done");
            }).subscribe(
                    result -> {
                        testContext.verify(() -> {
                            assertThat(workerHasContext.get())
                                    .as("Without TracedVertx, worker thread should have no OTel context")
                                    .isFalse();
                        });
                        testContext.completeNow();
                    },
                    testContext::failNow
            );
        } finally {
            parentSpan.end();
        }

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void handlerExceptionDoesNotLeakContext(VertxTestContext testContext) throws Exception {
        Span parentSpan = otel.getTracer().spanBuilder("server-request")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        try (Scope ignored = parentSpan.makeCurrent()) {
            TracedVertx.<String>rxExecuteBlocking(vertx, promise -> {
                promise.fail(new RuntimeException("worker failed"));
            }).subscribe(
                    result -> testContext.failNow(new AssertionError("Should not succeed")),
                    error -> {
                        testContext.verify(() -> {
                            assertThat(error.getMessage()).isEqualTo("worker failed");
                        });
                        testContext.completeNow();
                    }
            );
        } finally {
            parentSpan.end();
        }

        assertThat(testContext.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    }
}
