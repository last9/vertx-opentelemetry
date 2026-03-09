package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for Jedis Redis client.
 *
 * <p>Intercepts {@code redis.clients.jedis.Connection.sendCommand(ProtocolCommand, byte[]...)}
 * which is the single entry point for ALL Jedis Redis commands. This covers every
 * usage pattern: Jedis direct methods, JedisPool, JedisCluster, etc.
 *
 * <p>Uses Object typing to avoid compile-time dependency on Jedis classes.
 * The command name is extracted via reflection on the ProtocolCommand interface.
 */
public class JedisAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(0) Object command,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = JedisHelper.startSpan(command);
        if (span != null) {
            scope = span.makeCurrent();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        JedisHelper.endSpan(span, scope, thrown);
    }
}
