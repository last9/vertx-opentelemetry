package io.last9.tracing.otel.v4.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for ALL sync Aerospike operations on {@code AerospikeClient}.
 *
 * <p>Matches public methods whose first argument (index 0) is a Policy subclass
 * ({@code com.aerospike.client.policy.*}). This automatically covers all sync
 * operations: get, put, delete, exists, operate, touch, scanAll, query, execute,
 * append, prepend, add, getHeader — including future methods added to the API.
 *
 * <p>Replaces the previous approach of listing specific method names.
 */
public class AerospikeSyncAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Origin("#m") String methodName,
            @Advice.AllArguments Object[] args,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = AerospikeClientHelper.startSpanFromArgs(methodName.toUpperCase(), args);
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
