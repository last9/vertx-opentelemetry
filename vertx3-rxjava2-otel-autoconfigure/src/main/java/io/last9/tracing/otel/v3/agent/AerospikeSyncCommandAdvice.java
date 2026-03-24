package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code com.aerospike.client.command.SyncCommand.execute()}.
 *
 * <p>Instruments at the command execution level — every single-key Aerospike operation
 * (get, put, delete, exists, touch, operate, append, prepend) flows through
 * {@code SyncCommand.execute()}. This catches all usages regardless of whether the
 * caller uses {@code AerospikeClient}, {@code IAerospikeClient}, a subclass, or a proxy.
 *
 * <p>The operation name and key are extracted from the concrete command class
 * (ReadCommand, WriteCommand, etc.) by the helper.
 */
public class AerospikeSyncCommandAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.This Object command,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = AerospikeSyncCommandHelper.startSpan(command);
        if (span != null) {
            scope = span.makeCurrent();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        AerospikeClientHelper.endSpan(span, scope, thrown);
    }
}
