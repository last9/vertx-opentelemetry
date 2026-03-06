package io.last9.tracing.otel.v3.agent;

import com.aerospike.client.Key;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for single-key Aerospike data-plane methods on
 * {@code com.aerospike.client.AerospikeClient}.
 *
 * <p>Matches methods like {@code get(Policy, Key, ...)}, {@code put(WritePolicy, Key, ...)},
 * {@code delete(WritePolicy, Key)}, {@code exists(Policy, Key)},
 * {@code operate(WritePolicy, Key, ...)}, {@code touch(WritePolicy, Key)}.
 *
 * <p>Extracts the operation name from {@code @Advice.Origin("#m")} and the Key
 * from the second argument (index 1) of matched methods.
 */
public class AerospikeClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Origin("#m") String methodName,
            @Advice.Argument(1) Key key,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = AerospikeClientHelper.startSpan(methodName.toUpperCase(), key);
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
