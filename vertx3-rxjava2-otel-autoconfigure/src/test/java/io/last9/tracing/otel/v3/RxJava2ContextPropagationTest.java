package io.last9.tracing.otel.v3;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.*;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.Schedulers;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RxJava2ContextPropagationTest {

    private TestOtelSetup otel;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        RxJavaPlugins.reset();
        otel = new TestOtelSetup();
        tracer = otel.getTracer();
        resetInstalledFlag();
        RxJava2ContextPropagation.install();
    }

    @AfterEach
    void tearDown() {
        RxJavaPlugins.reset();
        otel.shutdown();
    }

    @Test
    void installIsIdempotent() {
        assertThat(RxJava2ContextPropagation.isInstalled()).isTrue();
        RxJava2ContextPropagation.install();
        assertThat(RxJava2ContextPropagation.isInstalled()).isTrue();
    }

    @Test
    void singlePropagatesContextOnSuccess() throws Exception {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Span span = tracer.spanBuilder("test-single").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Single<String> single = Single.just("value")
                    .map(v -> {
                        capturedTraceId.set(Span.current().getSpanContext().getTraceId());
                        return v;
                    });

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
                    .blockingGet();
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
            wrapped = RxJava2ContextPropagation.wrapWithContext(
                    () -> capturedTraceId.set(Span.current().getSpanContext().getTraceId()));
        }
        span.end();

        wrapped.run();

        assertThat(capturedTraceId.get()).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void wrapCallablePreservesContext() throws Exception {
        Span span = tracer.spanBuilder("test-wrap-callable").startSpan();
        java.util.concurrent.Callable<String> wrapped;
        try (Scope ignored = span.makeCurrent()) {
            wrapped = RxJava2ContextPropagation.wrapWithContext(
                    () -> Span.current().getSpanContext().getTraceId());
        }
        span.end();

        String capturedTraceId = wrapped.call();

        assertThat(capturedTraceId).isEqualTo(span.getSpanContext().getTraceId());
    }

    @Test
    void noWrappingWhenNoActiveContext() {
        assertThat(Context.current()).isEqualTo(Context.root());

        Single<String> single = Single.just("value");
        assertThat(single.blockingGet()).isEqualTo("value");
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
