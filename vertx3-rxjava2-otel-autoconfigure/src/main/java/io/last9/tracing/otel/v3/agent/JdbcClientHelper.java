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
     * @param sql the SQL statement
     * @return the span, or null if suppressed
     */
    public static Span startSpan(String sql) {
        if (AgentGuard.IN_DB_TRACED_CALL.get()) {
            return null;
        }

        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        String spanName = "jdbc " + truncateSql(sql);

        return tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, "other_sql")
                .setAttribute(SemanticAttributes.DB_STATEMENT, sql)
                .startSpan();
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

    private static String truncateSql(String sql) {
        if (sql == null) return "SQL";
        // Extract just the operation keyword (SELECT, INSERT, UPDATE, DELETE, etc.)
        String trimmed = sql.trim();
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            return trimmed.substring(0, spaceIdx).toUpperCase();
        }
        return trimmed.length() > 50 ? trimmed.substring(0, 50) : trimmed;
    }
}
