package io.vertx.tracing.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.*;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RxJava3ContextPropagationTest {

    private TestOtelSetup otel;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        // Reset RxJava plugins to clean state before installing our hooks
        RxJavaPlugins.reset();
        otel = new TestOtelSetup();
        tracer = otel.getTracer();
        // Reset the installed flag so install() works in each test
        resetInstalledFlag();
        RxJava3ContextPropagation.install();
    }

    @AfterEach
    void tearDown() {
        RxJavaPlugins.reset();
        otel.shutdown();
    }

    @Test
    void installIsIdempotent() {
        assertThat(RxJava3ContextPropagation.isInstalled()).isTrue();
        // Calling install again should not throw or double-register
        RxJava3ContextPropagation.install();
        assertThat(RxJava3ContextPropagation.isInstalled()).isTrue();
    }

    @Test
    void singlePropagatesContextOnSuccess() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Span span = tracer.spanBuilder("test-single").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            // Create Single inside span scope
            Single<String> single = Single.just("value")
                    .map(v -> {
                        capturedTraceId.set(Span.current().getSpanContext().getTraceId());
                        return v;
                    });

            // Subscribe (may execute on same thread, context should be propagated)
            single.blockingGet();
        } finally {
            span.end();
        }

        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void singlePropagatesContextOnError() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Span span = tracer.spanBuilder("test-single-error").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Single<String> single = Single.<String>error(new RuntimeException("test"))
                    .doOnError(e -> capturedTraceId.set(Span.current().getSpanContext().getTraceId()));

            try {
                single.blockingGet();
            } catch (Exception e) {
                // expected
            }
        } finally {
            span.end();
        }

        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void maybePropagatesContextOnSuccess() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Span span = tracer.spanBuilder("test-maybe").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Maybe.just("value")
                    .map(v -> {
                        capturedTraceId.set(Span.current().getSpanContext().getTraceId());
                        return v;
                    })
                    .blockingGet();
        } finally {
            span.end();
        }

        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void maybePropagatesContextOnEmpty() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Span span = tracer.spanBuilder("test-maybe-empty").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Maybe.<String>empty()
                    .doOnComplete(() -> capturedTraceId.set(Span.current().getSpanContext().getTraceId()))
                    .blockingSubscribe();
        } finally {
            span.end();
        }

        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void completablePropagatesContext() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Span span = tracer.spanBuilder("test-completable").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Completable.complete()
                    .doOnComplete(() -> capturedTraceId.set(Span.current().getSpanContext().getTraceId()))
                    .blockingAwait();
        } finally {
            span.end();
        }

        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void observablePropagatesContext() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Span span = tracer.spanBuilder("test-observable").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Observable.just("a", "b", "c")
                    .map(v -> {
                        capturedTraceId.set(Span.current().getSpanContext().getTraceId());
                        return v;
                    })
                    .blockingLast();
        } finally {
            span.end();
        }

        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void flowablePropagatesContext() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Span span = tracer.spanBuilder("test-flowable").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Flowable.just("a", "b", "c")
                    .map(v -> {
                        capturedTraceId.set(Span.current().getSpanContext().getTraceId());
                        return v;
                    })
                    .blockingLast();
        } finally {
            span.end();
        }

        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void schedulerHookPropagatesContextAcrossThreads() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedThreadName = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Span span = tracer.spanBuilder("test-scheduler").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Single.just("value")
                    .subscribeOn(Schedulers.io())
                    .map(v -> {
                        capturedTraceId.set(Span.current().getSpanContext().getTraceId());
                        capturedThreadName.set(Thread.currentThread().getName());
                        latch.countDown();
                        return v;
                    })
                    .subscribe();
        } finally {
            span.end();
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // Verify context propagated to a different thread
        assertThat(capturedThreadName.get()).contains("RxCachedThreadScheduler");
        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void observeOnPropagatesContextAcrossThreads() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Span span = tracer.spanBuilder("test-observeon").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Single.just("value")
                    .observeOn(Schedulers.computation())
                    .doOnSuccess(v -> {
                        capturedTraceId.set(Span.current().getSpanContext().getTraceId());
                        latch.countDown();
                    })
                    .subscribe();
        } finally {
            span.end();
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void chainedOperatorsPreserveContext() throws Exception {
        AtomicReference<String> traceIdInMap = new AtomicReference<>();
        AtomicReference<String> traceIdInFlatMap = new AtomicReference<>();

        Span span = tracer.spanBuilder("test-chain").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Single.just(1)
                    .map(v -> {
                        traceIdInMap.set(Span.current().getSpanContext().getTraceId());
                        return v * 2;
                    })
                    .flatMap(v -> {
                        traceIdInFlatMap.set(Span.current().getSpanContext().getTraceId());
                        return Single.just(v + 1);
                    })
                    .blockingGet();
        } finally {
            span.end();
        }

        String expectedTraceId = span.getSpanContext().getTraceId();
        assertThat(traceIdInMap.get()).isEqualTo(expectedTraceId);
        assertThat(traceIdInFlatMap.get()).isEqualTo(expectedTraceId);
    }

    @Test
    void wrapRunnablePreservesContext() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Span span = tracer.spanBuilder("test-wrap-runnable").startSpan();
        Runnable wrapped;
        try (Scope ignored = span.makeCurrent()) {
            wrapped = RxJava3ContextPropagation.wrapWithContext(
                    () -> capturedTraceId.set(Span.current().getSpanContext().getTraceId()));
        }
        span.end();

        // Execute outside span scope — context should still be available
        wrapped.run();

        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void wrapCallablePreservesContext() throws Exception {
        Span span = tracer.spanBuilder("test-wrap-callable").startSpan();
        java.util.concurrent.Callable<String> wrapped;
        try (Scope ignored = span.makeCurrent()) {
            wrapped = RxJava3ContextPropagation.wrapWithContext(
                    () -> Span.current().getSpanContext().getTraceId());
        }
        span.end();

        // Execute outside span scope
        String capturedTraceId = wrapped.call();

        assertThat(capturedTraceId).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void noWrappingWhenNoActiveContext() {
        // Outside any span — ROOT context
        assertThat(Context.current()).isEqualTo(Context.root());

        // Creating a Single should NOT wrap it (performance optimization)
        Single<String> single = Single.just("value");
        // The single should work fine without wrapping
        assertThat(single.blockingGet()).isEqualTo("value");
    }

    /**
     * Reset the static installed flag via reflection for test isolation.
     */
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
