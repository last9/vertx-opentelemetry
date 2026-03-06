package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.vertx.redis.client.Request;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code io.vertx.redis.client.impl.RedisConnectionImpl.send(Request, Handler)}.
 *
 * <p>Intercepts at the connection-level send method, which is the single entry point
 * for ALL Redis commands. This covers every usage pattern — RedisAPI high-level methods,
 * direct Request building, etc.
 *
 * <p>Uses {@link AgentGuard} to prevent double-instrumentation when the user is also
 * using {@code TracedRedisClient} (which delegates through DbTracing).
 */
public class RedisConnectionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(0) Request request,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = RedisConnectionHelper.startSpan(request);
        if (span != null) {
            scope = span.makeCurrent();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        RedisConnectionHelper.endSpan(span, scope, thrown);
    }
}
