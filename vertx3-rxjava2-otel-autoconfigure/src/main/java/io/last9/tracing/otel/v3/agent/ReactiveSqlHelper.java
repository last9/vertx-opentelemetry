package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.ExceptionAttributes;
import io.opentelemetry.semconv.SemanticAttributes;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper methods called by {@link ReactiveSqlAdvice} to create CLIENT spans
 * for Vert.x 3 reactive SQL operations (MySQLPool, PgPool, etc.).
 *
 * <p>Uses the Datadog-style "capture at creation time" pattern: connection metadata
 * (host, port, database, dbSystem) is captured when the pool is created via
 * {@link #registerPool(Object, Object)}, and looked up at query time from a
 * cached map keyed by pool identity. This avoids fragile field-walking at query time.
 *
 * <p>Intercepts at the {@code io.vertx.sqlclient.impl.SqlClientBase} level, covering
 * both {@code query(String)} and {@code preparedQuery(String)} methods. This is the
 * reactive SQL client API introduced in Vert.x 3.8+.
 *
 * <p>Uses the shared {@link AgentGuard} ThreadLocal to prevent double spans when the
 * user is already using {@code TracedMySQLClient}.
 */
public final class ReactiveSqlHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    /**
     * Cached connection metadata, keyed by {@code System.identityHashCode(pool)}.
     * Populated at pool creation time by {@link ReactiveSqlPoolAdvice}.
     */
    static final ConcurrentHashMap<Integer, DbConnectionInfo> POOL_METADATA = new ConcurrentHashMap<>();

    private ReactiveSqlHelper() {}

    /**
     * Immutable connection metadata captured at pool creation time.
     */
    static final class DbConnectionInfo {
        final String dbSystem;
        final String dbName;
        final String host;
        final int port;

        DbConnectionInfo(String dbSystem, String dbName, String host, int port) {
            this.dbSystem = dbSystem;
            this.dbName = dbName;
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Called from {@link ReactiveSqlPoolAdvice} when a pool is created.
     * Extracts host, port, database from the connect options and caches them.
     *
     * @param pool           the MySQLPoolImpl or PgPoolImpl instance
     * @param connectOptions the SqlConnectOptions (MySQLConnectOptions, PgConnectOptions)
     */
    public static void registerPool(Object pool, Object connectOptions) {
        if (pool == null || connectOptions == null) return;

        try {
            String dbSystem = detectDbSystem(pool);
            String dbName = invokeStringMethod(connectOptions, "getDatabase");
            String host = invokeStringMethod(connectOptions, "getHost");
            int port = invokeIntMethod(connectOptions, "getPort");

            POOL_METADATA.put(System.identityHashCode(pool),
                    new DbConnectionInfo(dbSystem, dbName, host, port));
        } catch (Exception ignored) {
            // Best-effort — don't break pool creation
        }
    }

    /**
     * Starts a CLIENT span for the given SQL operation.
     * Returns null if already inside a traced call (idempotency guard).
     *
     * @param sql    the SQL statement
     * @param client the SqlClientBase instance (pool or connection)
     * @return the span, or null if suppressed
     */
    public static Span startSpan(String sql, Object client) {
        if (AgentGuard.IN_DB_TRACED_CALL.get()) {
            return null;
        }

        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        // Look up cached metadata (DD-style: captured at pool creation)
        DbConnectionInfo info = client != null
                ? POOL_METADATA.get(System.identityHashCode(client))
                : null;

        String dbSystem;
        String dbName;
        String host;
        int port;

        if (info != null) {
            dbSystem = info.dbSystem;
            dbName = info.dbName;
            host = info.host;
            port = info.port;
        } else {
            // Fallback: detect from class name (no host/port available)
            dbSystem = client != null ? detectDbSystem(client) : "mysql";
            dbName = null;
            host = null;
            port = -1;
        }

        String spanName = io.last9.tracing.otel.v3.SqlSpanName.fromSql(sql, dbName);

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, dbSystem)
                .setAttribute(SemanticAttributes.DB_STATEMENT, sql)
                .startSpan();

        if (dbName != null) {
            span.setAttribute(SemanticAttributes.DB_NAME, dbName);
        }
        if (host != null) {
            span.setAttribute(SemanticAttributes.NET_PEER_NAME, host);
        }
        if (port > 0) {
            span.setAttribute(SemanticAttributes.NET_PEER_PORT, (long) port);
        }

        return span;
    }

    /**
     * Ends the span (success or error). Closes the scope if provided.
     */
    public static void endSpan(Span span, Scope scope, Throwable thrown) {
        if (span == null) return;
        try {
            if (thrown != null) {
                span.recordException(thrown,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, thrown.getMessage());
            }
        } finally {
            if (scope != null) {
                scope.close();
            }
            span.end();
        }
    }

    private static String detectDbSystem(Object obj) {
        String className = obj.getClass().getName().toLowerCase();
        if (className.contains("pg") || className.contains("postgres")) {
            return "postgresql";
        }
        return "mysql";
    }

    private static String invokeStringMethod(Object obj, String methodName) {
        try {
            return (String) obj.getClass().getMethod(methodName).invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private static int invokeIntMethod(Object obj, String methodName) {
        try {
            Object result = obj.getClass().getMethod(methodName).invoke(obj);
            return result instanceof Integer ? (Integer) result : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
