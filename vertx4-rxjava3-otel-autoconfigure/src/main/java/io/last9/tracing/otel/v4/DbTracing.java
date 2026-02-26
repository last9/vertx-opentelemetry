package io.last9.tracing.otel.v4;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.ExceptionAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.util.function.Supplier;

/**
 * Utility for adding OpenTelemetry tracing to database operations in Vert.x 4 applications.
 *
 * <p>Vert.x 4's {@code VertxTracer} SPI automatically traces HTTP client/server spans, but
 * database clients (PostgreSQL pool, MySQL, Aerospike, etc.) produce no spans automatically.
 * This utility wraps RxJava 3 database operations with a CLIENT span that captures
 * {@code db.system}, {@code db.statement}, and {@code db.name} using OpenTelemetry
 * semantic conventions.
 *
 * <p>Works with any database client — no dependency on a specific driver.
 *
 * <h2>Usage with PostgreSQL</h2>
 * <pre>{@code
 * DbTracing db = DbTracing.create("postgresql", "orders_db");
 *
 * db.traceSingle("SELECT * FROM orders WHERE id = $1", () ->
 *         pool.preparedQuery("SELECT * FROM orders WHERE id = $1")
 *             .rxExecute(Tuple.of(42)))
 *     .subscribe(rows -> { ... });
 * }</pre>
 *
 * <h2>Usage with a custom client</h2>
 * <pre>{@code
 * DbTracing db = DbTracing.create("aerospike", "my-namespace");
 *
 * db.traceSingle("GET user:123", () ->
 *         Single.fromCallable(() -> aerospikeClient.get(null, key)))
 *     .subscribe(record -> { ... });
 * }</pre>
 *
 * @see TracedDBPool for the ready-made database pool wrapper
 */
public final class DbTracing {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v4";

    private final String dbSystem;
    private final String dbNamespace;
    private final Tracer tracer;

    private DbTracing(String dbSystem, String dbNamespace, Tracer tracer) {
        this.dbSystem = dbSystem;
        this.dbNamespace = dbNamespace;
        this.tracer = tracer;
    }

    /**
     * Creates a {@code DbTracing} instance using {@link GlobalOpenTelemetry}.
     * Suitable for production use after {@link OtelLauncher} has initialised the SDK.
     *
     * @param dbSystem    the database system identifier (e.g., "postgresql", "mysql")
     * @param dbNamespace the database name (e.g., "orders_db"); may be {@code null} to omit
     * @return a new {@code DbTracing} instance
     */
    public static DbTracing create(String dbSystem, String dbNamespace) {
        return create(dbSystem, dbNamespace, GlobalOpenTelemetry.get());
    }

    /**
     * Creates a {@code DbTracing} instance using the supplied {@link OpenTelemetry} instance.
     * Useful in tests that construct their own {@code OpenTelemetrySdk}.
     *
     * @param dbSystem      the database system identifier
     * @param dbNamespace   the database name; may be {@code null} to omit
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a new {@code DbTracing} instance
     */
    public static DbTracing create(String dbSystem, String dbNamespace, OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        return new DbTracing(dbSystem, dbNamespace, tracer);
    }

    /**
     * Wraps an RxJava 3 {@link Single} database operation with a CLIENT span.
     *
     * @param <T>            the result type
     * @param operation      a short description of the operation (SQL or key lookup)
     * @param singleSupplier a supplier producing the Single to wrap (deferred so the span
     *                       is active when the operation starts)
     * @return a Single that emits the same result, wrapped with a tracing span
     */
    public <T> Single<T> traceSingle(String operation, Supplier<Single<T>> singleSupplier) {
        return Single.defer(() -> {
            Span span = startSpan(operation);
            Scope scope = span.makeCurrent();
            try {
                return singleSupplier.get()
                        .doOnError(err -> {
                            span.recordException(err,
                                    Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                            span.setStatus(StatusCode.ERROR, err.getMessage());
                        })
                        .doFinally(() -> {
                            scope.close();
                            span.end();
                        });
            } catch (Throwable t) {
                span.recordException(t,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, t.getMessage());
                scope.close();
                span.end();
                throw t;
            }
        });
    }

    /**
     * Wraps an RxJava 3 {@link Completable} database operation with a CLIENT span.
     *
     * @param operation           a short description of the operation
     * @param completableSupplier a supplier producing the Completable to wrap
     * @return a Completable wrapped with a tracing span
     */
    public Completable traceCompletable(String operation, Supplier<Completable> completableSupplier) {
        return Completable.defer(() -> {
            Span span = startSpan(operation);
            Scope scope = span.makeCurrent();
            try {
                return completableSupplier.get()
                        .doOnError(err -> {
                            span.recordException(err,
                                    Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                            span.setStatus(StatusCode.ERROR, err.getMessage());
                        })
                        .doFinally(() -> {
                            scope.close();
                            span.end();
                        });
            } catch (Throwable t) {
                span.recordException(t,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, t.getMessage());
                scope.close();
                span.end();
                throw t;
            }
        });
    }

    /**
     * Wraps an RxJava 3 {@link Maybe} database operation with a CLIENT span.
     *
     * @param <T>           the result type
     * @param operation     a short description of the operation
     * @param maybeSupplier a supplier producing the Maybe to wrap
     * @return a Maybe wrapped with a tracing span
     */
    public <T> Maybe<T> traceMaybe(String operation, Supplier<Maybe<T>> maybeSupplier) {
        return Maybe.defer(() -> {
            Span span = startSpan(operation);
            Scope scope = span.makeCurrent();
            try {
                return maybeSupplier.get()
                        .doOnError(err -> {
                            span.recordException(err,
                                    Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                            span.setStatus(StatusCode.ERROR, err.getMessage());
                        })
                        .doFinally(() -> {
                            scope.close();
                            span.end();
                        });
            } catch (Throwable t) {
                span.recordException(t,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, t.getMessage());
                scope.close();
                span.end();
                throw t;
            }
        });
    }

    private Span startSpan(String operation) {
        var builder = tracer.spanBuilder(dbSystem + " " + operation)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, dbSystem)
                .setAttribute(SemanticAttributes.DB_STATEMENT, operation);
        if (dbNamespace != null) {
            builder.setAttribute(SemanticAttributes.DB_NAME, dbNamespace);
        }
        return builder.startSpan();
    }
}
