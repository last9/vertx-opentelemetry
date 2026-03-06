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
     * @param sql the SQL statement
     * @return the span, or null if suppressed
     */
    public static Span startSpan(String sql) {
        if (AgentGuard.IN_DB_TRACED_CALL.get()) {
            return null;
        }

        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        String operation = extractOperation(sql);
        String spanName = "mysql " + operation;

        return tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, "mysql")
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

    private static String extractOperation(String sql) {
        if (sql == null) return "SQL";
        String trimmed = sql.trim();
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            return trimmed.substring(0, spaceIdx).toUpperCase();
        }
        return trimmed.length() > 50 ? trimmed.substring(0, 50) : trimmed;
    }
}
