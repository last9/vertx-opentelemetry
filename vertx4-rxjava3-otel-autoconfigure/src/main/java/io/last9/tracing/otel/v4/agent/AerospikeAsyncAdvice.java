package io.last9.tracing.otel.v4.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy advice for async Aerospike methods on {@code AerospikeClient}.
 *
 * <p>Matches public methods whose second argument (index 1) is an Aerospike
 * listener (e.g., {@code RecordListener}, {@code WriteListener}). These are
 * the async variants like {@code get(EventLoop, RecordListener, Policy, Key)}.
 *
 * <p>Creates a CLIENT span on enter, wraps the listener with a dynamic proxy
 * that ends the span when {@code onSuccess} or {@code onFailure} is called.
 */
public class AerospikeAsyncAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Origin("#m") String methodName,
            @Advice.AllArguments Object[] args,
            @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object listener,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = AerospikeClientHelper.startSpanFromArgs(methodName.toUpperCase(), args);
        if (span != null) {
            scope = span.makeCurrent();
            // Wrap listener to end span on callback
            listener = AerospikeClientHelper.wrapAsyncListener(listener, span);
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        if (scope != null) {
            scope.close();
        }
        // If method threw, end span immediately (listener won't fire)
        if (thrown != null && span != null) {
            AerospikeClientHelper.endSpan(span, null, thrown);
        }
    }
}
