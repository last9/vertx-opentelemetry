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
 * Helper methods called by {@link JdbcClientAdvice} to create CLIENT spans
 * for Vert.x 3 JDBC operations.
 *
 * <p>Intercepts at the {@code io.vertx.ext.jdbc.impl.JDBCClientImpl} level,
 * covering all SQL queries through the legacy {@code SQLClient} API
 * (used by JDBCClient, AsyncSQLClient, etc.).
 *
 * <p>Uses the shared {@link AgentGuard} ThreadLocal to prevent double spans when the
 * user is already using {@code TracedSQLClient} or {@code TracedSQLConnection}.
 */
public final class JdbcClientHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    private JdbcClientHelper() {}

    /**
     * Starts a CLIENT span for the given SQL operation.
     * Returns null if already inside a traced call (idempotency guard).
     *
     * @param sql    the SQL statement
     * @param client the JDBCClientImpl instance (for extracting db.name)
     * @return the span, or null if suppressed
     */
    public static Span startSpan(String sql, Object client) {
        if (AgentGuard.IN_DB_TRACED_CALL.get()) {
            return null;
        }

        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        String dbName = extractDbName(client);
        String spanName = io.last9.tracing.otel.v3.SqlSpanName.fromSql(sql, dbName);

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, "other_sql")
                .setAttribute(SemanticAttributes.DB_STATEMENT, sql)
                .startSpan();

        if (dbName != null) {
            span.setAttribute(SemanticAttributes.DB_NAME, dbName);
        }

        return span;
    }

    /**
     * Extracts the database name from a JDBCClientImpl's config.
     * Uses reflection to access the 'config' field and parse the JDBC URL.
     */
    private static String extractDbName(Object client) {
        if (client == null) return null;
        try {
            java.lang.reflect.Field configField = client.getClass().getDeclaredField("config");
            configField.setAccessible(true);
            Object config = configField.get(client);
            if (config == null) return null;

            String url = (String) config.getClass().getMethod("getString", String.class)
                    .invoke(config, "url");
            return parseDbNameFromJdbcUrl(url);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses the database name from a JDBC URL.
     * Examples: jdbc:postgresql://host:5432/mydb → mydb
     *           jdbc:mysql://host:3306/testdb?param=val → testdb
     */
    static String parseDbNameFromJdbcUrl(String url) {
        if (url == null) return null;
        try {
            // Strip "jdbc:" prefix, then parse as URI
            String withoutJdbc = url.startsWith("jdbc:") ? url.substring(5) : url;
            java.net.URI uri = new java.net.URI(withoutJdbc);
            String path = uri.getPath();
            if (path != null && path.length() > 1) {
                // Remove leading '/'
                return path.substring(1).split("[?;]")[0];
            }
        } catch (Exception ignored) {}
        return null;
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
