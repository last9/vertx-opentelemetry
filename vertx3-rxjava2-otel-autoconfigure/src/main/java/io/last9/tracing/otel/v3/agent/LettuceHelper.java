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
 * Helper methods called by {@link LettuceAdvice} to create CLIENT spans
 * for Lettuce Redis operations.
 *
 * <p>Intercepts at {@code AbstractRedisAsyncCommands.dispatch(RedisCommand)} level,
 * covering all Lettuce Redis commands regardless of sync/async/reactive API usage.
 *
 * <p>Uses reflection to extract the command type from the RedisCommand object,
 * avoiding compile-time dependency on Lettuce classes.
 */
public final class LettuceHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    private LettuceHelper() {}

    /**
     * Starts a CLIENT span for the given Lettuce RedisCommand.
     *
     * @param redisCommand the RedisCommand object
     * @return the span, or null if suppressed
     */
    public static Span startSpan(Object redisCommand) {
        if (AgentGuard.IN_DB_TRACED_CALL.get()) {
            return null;
        }

        String commandName = extractCommandName(redisCommand);
        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        return tracer.spanBuilder("redis " + commandName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, "redis")
                .setAttribute(SemanticAttributes.DB_STATEMENT, commandName)
                .startSpan();
    }

    /**
     * Extracts the command name from a Lettuce RedisCommand via reflection.
     * RedisCommand.getType() returns a ProtocolKeyword with a name() method.
     */
    private static String extractCommandName(Object redisCommand) {
        if (redisCommand == null) return "UNKNOWN";
        try {
            // RedisCommand.getType() → ProtocolKeyword (CommandType enum)
            Object type = redisCommand.getClass().getMethod("getType").invoke(redisCommand);
            if (type != null) {
                return type.getClass().getMethod("name").invoke(type).toString();
            }
        } catch (Exception ignored) {}
        return "UNKNOWN";
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
