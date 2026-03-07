package io.last9.tracing.otel.v3;

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
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;

import io.last9.tracing.otel.v3.agent.AgentGuard;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Utility for adding OpenTelemetry tracing to database operations in Vert.x 3 applications.
 *
 * <p>Vert.x 3 has no {@code VertxTracer} SPI, so database clients (MySQL, Aerospike, etc.)
 * produce no spans automatically. This utility wraps RxJava 2 database operations with a
 * CLIENT span that captures {@code db.system}, {@code db.statement}, and {@code db.name}
 * using OpenTelemetry semantic conventions.
 *
 * <p>Works with any database client — no dependency on a specific driver.
 *
 * <h2>Usage with MySQL</h2>
 * <pre>{@code
 * DbTracing db = DbTracing.create("mysql", "orders_db");
 *
 * db.traceSingle("SELECT * FROM orders WHERE id = ?", () ->
 *         sqlClient.rxQueryWithParams("SELECT * FROM orders WHERE id = ?", params))
 *     .subscribe(resultSet -> { ... });
 * }</pre>
 *
 * <h2>Usage with Aerospike</h2>
 * <pre>{@code
 * DbTracing db = DbTracing.create("aerospike", "my-namespace");
 *
 * db.traceSingle("GET user:123", () ->
 *         Single.fromCallable(() -> aerospikeClient.get(null, key)))
 *     .subscribe(record -> { ... });
 * }</pre>
 *
 * @see TracedRouter
 * @see ClientTracing
 */
public final class DbTracing {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    private final String dbSystem;
    private final String dbNamespace;
    private final Tracer tracer;

    private DbTracing(String dbSystem, String dbNamespace, Tracer tracer) {
        this.dbSystem = dbSystem;
        this.dbNamespace = dbNamespace;
        this.tracer = tracer;
    }

    /**
     * Creates a {@code DbTracing} instance for the given database system and namespace.
     *
     * <p>Uses {@link GlobalOpenTelemetry#get()} — suitable for production use after
     * {@link OtelLauncher} has initialised the SDK.
     *
     * @param dbSystem   the database system identifier (e.g., "mysql", "aerospike", "postgresql")
     * @param dbNamespace the database or namespace name (e.g., "orders_db", "my-namespace")
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
     * @param dbNamespace   the database or namespace name
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a new {@code DbTracing} instance
     */
    public static DbTracing create(String dbSystem, String dbNamespace, OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        return new DbTracing(dbSystem, dbNamespace, tracer);
    }

    /**
     * Wraps an RxJava {@link Single} database operation with a CLIENT span.
     *
     * <p>The span is started before subscribing and ended when the Single terminates
     * (either success or error). On error, the exception is recorded on the span.
     *
     * @param <T>         the result type
     * @param operation   a short description of the operation (e.g., the SQL statement or key lookup)
     * @param singleSupplier a supplier that produces the Single to wrap (deferred so the span
     *                       is current when the operation starts)
     * @return a Single that emits the same result, wrapped with a tracing span
     */
    public <T> Single<T> traceSingle(String operation, Supplier<Single<T>> singleSupplier) {
        return Single.defer(() -> {
            Span span = startSpan(operation);
            Scope scope = span.makeCurrent();
            try {
                AgentGuard.IN_DB_TRACED_CALL.set(true);
                Single<T> result;
                try {
                    result = singleSupplier.get();
                } finally {
                    AgentGuard.IN_DB_TRACED_CALL.set(false);
                }
                return result
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
                AgentGuard.IN_DB_TRACED_CALL.set(false);
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
     * Wraps an RxJava {@link Completable} database operation with a CLIENT span.
     *
     * @param operation   a short description of the operation
     * @param completableSupplier a supplier that produces the Completable to wrap
     * @return a Completable wrapped with a tracing span
     */
    public Completable traceCompletable(String operation, Supplier<Completable> completableSupplier) {
        return Completable.defer(() -> {
            Span span = startSpan(operation);
            Scope scope = span.makeCurrent();
            try {
                AgentGuard.IN_DB_TRACED_CALL.set(true);
                Completable result;
                try {
                    result = completableSupplier.get();
                } finally {
                    AgentGuard.IN_DB_TRACED_CALL.set(false);
                }
                return result
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
                AgentGuard.IN_DB_TRACED_CALL.set(false);
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
     * Wraps an RxJava {@link Maybe} database operation with a CLIENT span.
     *
     * @param <T>         the result type
     * @param operation   a short description of the operation
     * @param maybeSupplier a supplier that produces the Maybe to wrap
     * @return a Maybe wrapped with a tracing span
     */
    public <T> Maybe<T> traceMaybe(String operation, Supplier<Maybe<T>> maybeSupplier) {
        return Maybe.defer(() -> {
            Span span = startSpan(operation);
            Scope scope = span.makeCurrent();
            try {
                AgentGuard.IN_DB_TRACED_CALL.set(true);
                Maybe<T> result;
                try {
                    result = maybeSupplier.get();
                } finally {
                    AgentGuard.IN_DB_TRACED_CALL.set(false);
                }
                return result
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
                AgentGuard.IN_DB_TRACED_CALL.set(false);
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
     * Wraps a synchronous (blocking) database operation with a CLIENT span.
     *
     * <p>Useful for clients that do not return RxJava types (e.g., the Aerospike
     * synchronous client). The span wraps the entire call duration.
     *
     * <pre>{@code
     * Record result = db.traceSync("GET user:123", () -> aerospikeClient.get(null, key));
     * }</pre>
     *
     * @param <T>       the result type
     * @param operation a short description of the operation
     * @param supplier  the blocking operation to execute
     * @return the result of the operation
     */
    public <T> T traceSync(String operation, Supplier<T> supplier) {
        Span span = startSpan(operation);
        try (Scope ignored = span.makeCurrent()) {
            // Set guard so bytecode agent advice skips the raw client call
            // that our delegate wrapper is about to make.
            AgentGuard.IN_DB_TRACED_CALL.set(true);
            T result;
            try {
                result = supplier.get();
            } finally {
                AgentGuard.IN_DB_TRACED_CALL.set(false);
            }
            return result;
        } catch (Throwable t) {
            span.recordException(t,
                    Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
            span.setStatus(StatusCode.ERROR, t.getMessage());
            throw t;
        } finally {
            span.end();
        }
    }

    private static final Set<String> SQL_SYSTEMS = Set.of(
            "mysql", "postgresql", "mariadb", "mssql", "oracle", "sqlite",
            "db2", "h2", "hsqldb", "other_sql");

    private Span startSpan(String operation) {
        String spanName;
        if (SQL_SYSTEMS.contains(dbSystem) && SqlSpanName.looksLikeSql(operation)) {
            spanName = SqlSpanName.fromSql(operation, dbNamespace);
        } else {
            // Non-SQL systems or non-SQL operation strings
            spanName = dbSystem + " " + operation;
        }
        return tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, dbSystem)
                .setAttribute(SemanticAttributes.DB_STATEMENT, operation)
                .setAttribute(SemanticAttributes.DB_NAME, dbNamespace)
                .startSpan();
    }
}
