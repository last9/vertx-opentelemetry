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
 * Helper methods called by {@link JedisAdvice} to create CLIENT spans
 * for Jedis Redis operations.
 *
 * <p>Intercepts at the {@code redis.clients.jedis.Connection.sendCommand()} level,
 * covering all Redis commands regardless of which Jedis API the application uses
 * (Jedis, JedisPool, JedisCluster, Pipeline, etc.).
 *
 * <p>Uses reflection to extract the command name from the ProtocolCommand argument,
 * avoiding compile-time dependency on Jedis classes.
 */
public final class JedisHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    private JedisHelper() {}

    /**
     * Starts a CLIENT span for the given Jedis command.
     *
     * @param command the ProtocolCommand object (Jedis enum)
     * @return the span, or null if suppressed
     */
    public static Span startSpan(Object command) {
        if (AgentGuard.IN_DB_TRACED_CALL.get()) {
            return null;
        }

        String commandName = extractCommandName(command);
        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        return tracer.spanBuilder("redis " + commandName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, "redis")
                .setAttribute(SemanticAttributes.DB_STATEMENT, commandName)
                .startSpan();
    }

    /**
     * Extracts the command name from a Jedis ProtocolCommand via reflection.
     * ProtocolCommand has getRaw() returning byte[] in Jedis 3.x+,
     * and name() from the enum in all versions.
     */
    private static String extractCommandName(Object command) {
        if (command == null) return "UNKNOWN";
        try {
            // Try name() first (works for enums like Protocol.Command)
            return command.getClass().getMethod("name").invoke(command).toString();
        } catch (Exception e1) {
            try {
                // Fallback: getRaw() returns byte[] command name
                byte[] raw = (byte[]) command.getClass().getMethod("getRaw").invoke(command);
                return new String(raw).toUpperCase();
            } catch (Exception e2) {
                return command.toString().toUpperCase();
            }
        }
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
