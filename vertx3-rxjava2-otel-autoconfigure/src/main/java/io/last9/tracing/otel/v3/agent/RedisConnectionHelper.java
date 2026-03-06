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
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;

import java.nio.charset.StandardCharsets;

/**
 * Helper methods called by {@link RedisConnectionAdvice} to create CLIENT spans
 * for Vert.x Redis operations.
 *
 * <p>Intercepts at the raw {@code io.vertx.redis.client.impl.RedisConnectionImpl.send()}
 * level, covering all Redis commands regardless of which API layer the application uses
 * (RedisAPI, Redis client, etc.).
 *
 * <p>Uses the shared {@link AgentGuard} ThreadLocal to prevent double spans when the
 * user is already using {@code TracedRedisClient} (which delegates to the same connection).
 */
public final class RedisConnectionHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    private RedisConnectionHelper() {}

    /**
     * Starts a CLIENT span for the given Redis request.
     * Returns null if already inside a traced call (idempotency guard).
     *
     * @param request the Redis Request object
     * @return the span, or null if suppressed
     */
    public static Span startSpan(Request request) {
        if (AgentGuard.IN_DB_TRACED_CALL.get()) {
            return null;
        }

        String commandName = extractCommandName(request);
        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        return tracer.spanBuilder("redis " + commandName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.DB_SYSTEM, "redis")
                .setAttribute(SemanticAttributes.DB_STATEMENT, commandName)
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

    private static String extractCommandName(Request request) {
        try {
            Command cmd = request.command();
            if (cmd == null) return "UNKNOWN";
            // Command.getBytes() returns RESP protocol encoding like "$3\r\nGET\r\n".
            // Extract the command name from between the \n delimiters.
            byte[] bytes = cmd.getBytes();
            if (bytes == null) return "UNKNOWN";
            String raw = new String(bytes, StandardCharsets.UTF_8);
            // Find first \n (after $N prefix), then second \n (end of command name)
            int start = raw.indexOf('\n');
            if (start < 0) return raw.trim().toUpperCase();
            int end = raw.indexOf('\r', start + 1);
            if (end < 0) end = raw.indexOf('\n', start + 1);
            if (end < 0) end = raw.length();
            return raw.substring(start + 1, end).trim().toUpperCase();
        } catch (Throwable t) {
            return "UNKNOWN";
        }
    }
}
