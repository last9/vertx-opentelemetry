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
 * Helper methods called by {@link JdbcStatementAdvice} to create CLIENT spans
 * for raw JDBC Statement operations.
 *
 * <p>Covers any JDBC driver (MySQL, PostgreSQL, H2, Oracle, etc.) by intercepting
 * at the {@code java.sql.Statement} interface level. Extracts the database name
 * from the Statement's connection metadata when possible.
 *
 * <p>Uses the shared {@link AgentGuard} ThreadLocal to prevent double spans when
 * also using Vert.x JDBC instrumentation or TracedSQLClient.
 */
public final class JdbcStatementHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    private JdbcStatementHelper() {}

    /**
     * Starts a CLIENT span for the given SQL operation on a raw JDBC Statement.
     * Returns null if already inside a traced call (idempotency guard).
     */
    public static Span startSpan(String sql, Object statement) {
        if (AgentGuard.IN_DB_TRACED_CALL.get()) {
            return null;
        }

        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        String dbSystem = extractDbSystem(statement);
        String dbName = extractDbName(statement);
        String spanName = io.last9.tracing.otel.v3.SqlSpanName.fromSql(sql, dbName);

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, dbSystem != null ? dbSystem : "other_sql")
                .setAttribute(SemanticAttributes.DB_STATEMENT, sql)
                .startSpan();

        if (dbName != null) {
            span.setAttribute(SemanticAttributes.DB_NAME, dbName);
        }

        return span;
    }

    /**
     * Extracts the database system (mysql, postgresql, etc.) from the Statement's
     * connection metadata. Falls back to "other_sql" if unavailable.
     */
    private static String extractDbSystem(Object statement) {
        try {
            Object connection = statement.getClass().getMethod("getConnection").invoke(statement);
            Object metadata = connection.getClass().getMethod("getMetaData").invoke(connection);
            String url = (String) metadata.getClass().getMethod("getURL").invoke(metadata);
            if (url != null) {
                // jdbc:mysql://... → mysql, jdbc:postgresql://... → postgresql
                String withoutJdbc = url.startsWith("jdbc:") ? url.substring(5) : url;
                int colonIdx = withoutJdbc.indexOf(':');
                if (colonIdx > 0) {
                    return withoutJdbc.substring(0, colonIdx).toLowerCase();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Extracts the database name from the Statement's connection metadata.
     */
    private static String extractDbName(Object statement) {
        try {
            Object connection = statement.getClass().getMethod("getConnection").invoke(statement);
            Object metadata = connection.getClass().getMethod("getMetaData").invoke(connection);
            String url = (String) metadata.getClass().getMethod("getURL").invoke(metadata);
            return JdbcClientHelper.parseDbNameFromJdbcUrl(url);
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
