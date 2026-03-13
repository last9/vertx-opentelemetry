package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for Lettuce async Redis client.
 *
 * <p>Intercepts {@code io.lettuce.core.AbstractRedisAsyncCommands.dispatch(RedisCommand)}
 * which is the single entry point for ALL Lettuce Redis commands. This covers every
 * usage pattern: sync, async, reactive, and pipelining.
 *
 * <p>Uses Object typing to avoid compile-time dependency on Lettuce classes.
 */
public class LettuceAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(0) Object redisCommand,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = LettuceHelper.startSpan(redisCommand);
        if (span != null) {
            scope = span.makeCurrent();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        LettuceHelper.endSpan(span, scope, thrown);
    }
}
