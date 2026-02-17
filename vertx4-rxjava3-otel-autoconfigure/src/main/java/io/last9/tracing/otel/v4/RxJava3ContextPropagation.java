package io.last9.tracing.otel.v4;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.*;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enables automatic OpenTelemetry context propagation for RxJava3.
 *
 * <p>This class hooks into RxJava's plugin system to automatically capture and restore
 * OpenTelemetry context across RxJava operators and schedulers. This is critical for
 * Vert.x applications because RxJava operators can switch threads, which would normally
 * cause the trace context to be lost.
 *
 * <p>The hooks ensure that:
 * <ul>
 *   <li>Context is captured when a reactive stream is created</li>
 *   <li>Context is restored when operators execute on different threads</li>
 *   <li>Scheduler-submitted tasks run with the correct context</li>
 * </ul>
 *
 * <p>Call {@link #install()} once at application startup. This is handled automatically
 * by {@link OtelLauncher}.
 *
 * @see OtelLauncher
 */
public class RxJava3ContextPropagation {

    private static final Logger log = LoggerFactory.getLogger(RxJava3ContextPropagation.class);
    private static final AtomicBoolean installed = new AtomicBoolean(false);

    /**
     * Install OpenTelemetry context propagation hooks for RxJava3.
     * Safe to call multiple times - will only register hooks once.
     */
    public static void install() {
        if (installed.compareAndSet(false, true)) {
            registerHooks();
            log.debug("RxJava3 OpenTelemetry context propagation hooks installed");
        }
    }

    /**
     * Check if context propagation hooks are installed.
     *
     * @return true if hooks are installed
     */
    public static boolean isInstalled() {
        return installed.get();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerHooks() {
        // Hook for Single - most commonly used in Vert.x
        Function existingSingleHook = RxJavaPlugins.getOnSingleAssembly();
        RxJavaPlugins.setOnSingleAssembly(single -> {
            if (!hasActiveContext()) return existingSingleHook != null ? (Single) existingSingleHook.apply(single) : single;
            Single result = new ContextPropagationSingle<>(single);
            return existingSingleHook != null ? (Single) existingSingleHook.apply(result) : result;
        });

        // Hook for Maybe
        Function existingMaybeHook = RxJavaPlugins.getOnMaybeAssembly();
        RxJavaPlugins.setOnMaybeAssembly(maybe -> {
            if (!hasActiveContext()) return existingMaybeHook != null ? (Maybe) existingMaybeHook.apply(maybe) : maybe;
            Maybe result = new ContextPropagationMaybe<>(maybe);
            return existingMaybeHook != null ? (Maybe) existingMaybeHook.apply(result) : result;
        });

        // Hook for Completable
        Function existingCompletableHook = RxJavaPlugins.getOnCompletableAssembly();
        RxJavaPlugins.setOnCompletableAssembly(completable -> {
            if (!hasActiveContext()) return existingCompletableHook != null ? (Completable) existingCompletableHook.apply(completable) : completable;
            Completable result = new ContextPropagationCompletable(completable);
            return existingCompletableHook != null ? (Completable) existingCompletableHook.apply(result) : result;
        });

        // Hook for Observable
        Function existingObservableHook = RxJavaPlugins.getOnObservableAssembly();
        RxJavaPlugins.setOnObservableAssembly(observable -> {
            if (!hasActiveContext()) return existingObservableHook != null ? (Observable) existingObservableHook.apply(observable) : observable;
            Observable result = new ContextPropagationObservable<>(observable);
            return existingObservableHook != null ? (Observable) existingObservableHook.apply(result) : result;
        });

        // Hook for Flowable
        Function existingFlowableHook = RxJavaPlugins.getOnFlowableAssembly();
        RxJavaPlugins.setOnFlowableAssembly(flowable -> {
            if (!hasActiveContext()) return existingFlowableHook != null ? (Flowable) existingFlowableHook.apply(flowable) : flowable;
            Flowable result = new ContextPropagationFlowable<>(flowable);
            return existingFlowableHook != null ? (Flowable) existingFlowableHook.apply(result) : result;
        });

        // Hook for schedulers - wrap scheduled runnables with context
        // This is critical for subscribeOn/observeOn operators
        RxJavaPlugins.setScheduleHandler(RxJava3ContextPropagation::wrapWithContext);
    }

    /**
     * Check if there is an active (non-root) OpenTelemetry context.
     * Avoids wrapping overhead when no trace is active.
     */
    private static boolean hasActiveContext() {
        return Context.current() != Context.root();
    }

    /**
     * Wrap a Runnable to preserve OpenTelemetry context across thread boundaries.
     *
     * @param runnable the runnable to wrap
     * @return a context-preserving runnable
     */
    public static Runnable wrapWithContext(Runnable runnable) {
        Context context = Context.current();
        return () -> {
            try (Scope ignored = context.makeCurrent()) {
                runnable.run();
            }
        };
    }

    /**
     * Wrap a Callable to preserve OpenTelemetry context across thread boundaries.
     *
     * @param callable the callable to wrap
     * @param <T> the return type
     * @return a context-preserving callable
     */
    public static <T> Callable<T> wrapWithContext(Callable<T> callable) {
        Context context = Context.current();
        return () -> {
            try (Scope ignored = context.makeCurrent()) {
                return callable.call();
            }
        };
    }

    // ============ Context-propagating RxJava wrapper types ============

    private static class ContextPropagationSingle<T> extends Single<T> {
        private final Single<T> source;
        private final Context context;

        ContextPropagationSingle(Single<T> source) {
            this.source = source;
            this.context = Context.current();
        }

        @Override
        protected void subscribeActual(SingleObserver<? super T> observer) {
            try (Scope ignored = context.makeCurrent()) {
                source.subscribe(new ContextPropagationSingleObserver<>(observer, context));
            }
        }
    }

    private static class ContextPropagationSingleObserver<T> implements SingleObserver<T> {
        private final SingleObserver<? super T> downstream;
        private final Context context;

        ContextPropagationSingleObserver(SingleObserver<? super T> downstream, Context context) {
            this.downstream = downstream;
            this.context = context;
        }

        @Override
        public void onSubscribe(io.reactivex.rxjava3.disposables.Disposable d) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onSubscribe(d);
            }
        }

        @Override
        public void onSuccess(T t) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onSuccess(t);
            }
        }

        @Override
        public void onError(Throwable e) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onError(e);
            }
        }
    }

    private static class ContextPropagationMaybe<T> extends Maybe<T> {
        private final Maybe<T> source;
        private final Context context;

        ContextPropagationMaybe(Maybe<T> source) {
            this.source = source;
            this.context = Context.current();
        }

        @Override
        protected void subscribeActual(MaybeObserver<? super T> observer) {
            try (Scope ignored = context.makeCurrent()) {
                source.subscribe(new ContextPropagationMaybeObserver<>(observer, context));
            }
        }
    }

    private static class ContextPropagationMaybeObserver<T> implements MaybeObserver<T> {
        private final MaybeObserver<? super T> downstream;
        private final Context context;

        ContextPropagationMaybeObserver(MaybeObserver<? super T> downstream, Context context) {
            this.downstream = downstream;
            this.context = context;
        }

        @Override
        public void onSubscribe(io.reactivex.rxjava3.disposables.Disposable d) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onSubscribe(d);
            }
        }

        @Override
        public void onSuccess(T t) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onSuccess(t);
            }
        }

        @Override
        public void onError(Throwable e) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onError(e);
            }
        }

        @Override
        public void onComplete() {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onComplete();
            }
        }
    }

    private static class ContextPropagationCompletable extends Completable {
        private final Completable source;
        private final Context context;

        ContextPropagationCompletable(Completable source) {
            this.source = source;
            this.context = Context.current();
        }

        @Override
        protected void subscribeActual(CompletableObserver observer) {
            try (Scope ignored = context.makeCurrent()) {
                source.subscribe(new ContextPropagationCompletableObserver(observer, context));
            }
        }
    }

    private static class ContextPropagationCompletableObserver implements CompletableObserver {
        private final CompletableObserver downstream;
        private final Context context;

        ContextPropagationCompletableObserver(CompletableObserver downstream, Context context) {
            this.downstream = downstream;
            this.context = context;
        }

        @Override
        public void onSubscribe(io.reactivex.rxjava3.disposables.Disposable d) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onSubscribe(d);
            }
        }

        @Override
        public void onComplete() {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onComplete();
            }
        }

        @Override
        public void onError(Throwable e) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onError(e);
            }
        }
    }

    private static class ContextPropagationObservable<T> extends Observable<T> {
        private final Observable<T> source;
        private final Context context;

        ContextPropagationObservable(Observable<T> source) {
            this.source = source;
            this.context = Context.current();
        }

        @Override
        protected void subscribeActual(Observer<? super T> observer) {
            try (Scope ignored = context.makeCurrent()) {
                source.subscribe(new ContextPropagationObserver<>(observer, context));
            }
        }
    }

    private static class ContextPropagationObserver<T> implements Observer<T> {
        private final Observer<? super T> downstream;
        private final Context context;

        ContextPropagationObserver(Observer<? super T> downstream, Context context) {
            this.downstream = downstream;
            this.context = context;
        }

        @Override
        public void onSubscribe(io.reactivex.rxjava3.disposables.Disposable d) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onSubscribe(d);
            }
        }

        @Override
        public void onNext(T t) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onNext(t);
            }
        }

        @Override
        public void onError(Throwable e) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onError(e);
            }
        }

        @Override
        public void onComplete() {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onComplete();
            }
        }
    }

    private static class ContextPropagationFlowable<T> extends Flowable<T> {
        private final Flowable<T> source;
        private final Context context;

        ContextPropagationFlowable(Flowable<T> source) {
            this.source = source;
            this.context = Context.current();
        }

        @Override
        protected void subscribeActual(org.reactivestreams.Subscriber<? super T> subscriber) {
            try (Scope ignored = context.makeCurrent()) {
                source.subscribe(new ContextPropagationSubscriber<>(subscriber, context));
            }
        }
    }

    private static class ContextPropagationSubscriber<T> implements FlowableSubscriber<T> {
        private final org.reactivestreams.Subscriber<? super T> downstream;
        private final Context context;

        ContextPropagationSubscriber(org.reactivestreams.Subscriber<? super T> downstream, Context context) {
            this.downstream = downstream;
            this.context = context;
        }

        @Override
        public void onSubscribe(org.reactivestreams.Subscription s) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onSubscribe(s);
            }
        }

        @Override
        public void onNext(T t) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onNext(t);
            }
        }

        @Override
        public void onError(Throwable e) {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onError(e);
            }
        }

        @Override
        public void onComplete() {
            try (Scope ignored = context.makeCurrent()) {
                downstream.onComplete();
            }
        }
    }
}
