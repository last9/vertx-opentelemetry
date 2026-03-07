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

/**
 * Helper methods called by {@link ReactiveSqlAdvice} to create CLIENT spans
 * for Vert.x 3 reactive SQL operations (MySQLPool, PgPool, etc.).
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

    private ReactiveSqlHelper() {}

    /**
     * Starts a CLIENT span for the given SQL operation.
     * Returns null if already inside a traced call (idempotency guard).
     *
     * @param sql    the SQL statement
     * @param client the SqlClientBase instance (for extracting db.name and db.system)
     * @return the span, or null if suppressed
     */
    public static Span startSpan(String sql, Object client) {
        if (AgentGuard.IN_DB_TRACED_CALL.get()) {
            return null;
        }

        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        String[] dbInfo = extractDbInfo(client);
        String dbSystem = dbInfo[0];
        String dbName = dbInfo[1];
        String spanName = io.last9.tracing.otel.v3.SqlSpanName.fromSql(sql, dbName);

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, dbSystem)
                .setAttribute(SemanticAttributes.DB_STATEMENT, sql)
                .startSpan();

        if (dbName != null) {
            span.setAttribute(SemanticAttributes.DB_NAME, dbName);
        }

        return span;
    }

    /**
     * Extracts db.system and db.name from the reactive SQL client via reflection.
     * Walks up the class hierarchy to find connection options fields.
     *
     * @return [dbSystem, dbName] — dbName may be null
     */
    private static String[] extractDbInfo(Object client) {
        String dbSystem = "mysql";
        String dbName = null;

        if (client == null) return new String[]{dbSystem, dbName};

        try {
            // Detect db.system from class name
            String className = client.getClass().getName().toLowerCase();
            if (className.contains("pg") || className.contains("postgres")) {
                dbSystem = "postgresql";
            } else if (className.contains("mysql")) {
                dbSystem = "mysql";
            }

            // Try to get the database name from connection options.
            // SqlClientBase stores connectOptions or similar fields depending on version.
            // Try reflection to find a getDatabase() method on the options object.
            for (java.lang.reflect.Field f : getAllFields(client.getClass())) {
                f.setAccessible(true);
                Object val = f.get(client);
                if (val == null) continue;

                // Look for SqlConnectOptions or subclass with getDatabase()
                try {
                    java.lang.reflect.Method getDb = val.getClass().getMethod("getDatabase");
                    String db = (String) getDb.invoke(val);
                    if (db != null && !db.isEmpty()) {
                        dbName = db;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception ignored) {}

        return new String[]{dbSystem, dbName};
    }

    private static java.util.List<java.lang.reflect.Field> getAllFields(Class<?> clazz) {
        java.util.List<java.lang.reflect.Field> fields = new java.util.ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                fields.add(f);
            }
            clazz = clazz.getSuperclass();
        }
        return fields;
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

}
