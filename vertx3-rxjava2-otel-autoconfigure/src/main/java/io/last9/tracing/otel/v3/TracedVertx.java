package io.last9.tracing.otel.v3;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.Maybe;
import io.vertx.core.Handler;
import io.vertx.reactivex.core.Promise;
import io.vertx.reactivex.core.Vertx;

/**
 * Utility for running blocking operations on Vert.x worker threads with
 * OpenTelemetry context propagation.
 *
 * <p>Vert.x's {@code rxExecuteBlocking()} dispatches work to a worker thread pool.
 * Since OpenTelemetry context is thread-local, the worker thread has no access to
 * the active span from the event loop. This means traced clients like
 * {@link TracedAerospikeClient} produce disconnected spans — they start a new root
 * trace instead of parenting under the current request's SERVER span.
 *
 * <p>{@code TracedVertx.rxExecuteBlocking()} captures the OTel context on the
 * event loop and restores it on the worker thread automatically.
 *
 * <h2>Before (manual boilerplate)</h2>
 * <pre>{@code
 * Context otelCtx = Context.current();  // capture on event loop
 * vertx.<Record>rxExecuteBlocking(promise -> {
 *     try (Scope ignored = otelCtx.makeCurrent()) {  // restore on worker
 *         Record r = aerospikeClient.get(null, key);
 *         promise.complete(r);
 *     }
 * });
 * }</pre>
 *
 * <h2>After (with TracedVertx)</h2>
 * <pre>{@code
 * TracedVertx.<Record>rxExecuteBlocking(vertx, promise -> {
 *     Record r = aerospikeClient.get(null, key);
 *     promise.complete(r);
 * });
 * }</pre>
 *
 * @see TracedAerospikeClient
 * @see DbTracing
 */
public final class TracedVertx {

    private TracedVertx() {
        // Utility class
    }

    /**
     * Executes a blocking operation on a Vert.x worker thread with OTel context propagation.
     *
     * <p>Captures {@link Context#current()} on the calling thread (event loop) and
     * makes it current on the worker thread before invoking the handler. The context
     * scope is closed after the handler completes (success or failure).
     *
     * @param <T>     the result type
     * @param vertx   the Vert.x instance
     * @param handler the blocking handler to execute
     * @return a Maybe that emits the handler's result
     */
    public static <T> Maybe<T> rxExecuteBlocking(Vertx vertx, Handler<Promise<T>> handler) {
        return rxExecuteBlocking(vertx, handler, true);
    }

    /**
     * Executes a blocking operation on a Vert.x worker thread with OTel context propagation.
     *
     * @param <T>     the result type
     * @param vertx   the Vert.x instance
     * @param handler the blocking handler to execute
     * @param ordered if true, handlers are executed serially on the same worker thread
     * @return a Maybe that emits the handler's result
     */
    public static <T> Maybe<T> rxExecuteBlocking(Vertx vertx, Handler<Promise<T>> handler,
                                                   boolean ordered) {
        Context otelCtx = Context.current();
        return vertx.rxExecuteBlocking(promise -> {
            try (Scope ignored = otelCtx.makeCurrent()) {
                handler.handle(promise);
            }
        }, ordered);
    }
}
