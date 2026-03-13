package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for batch Aerospike data-plane methods on
 * {@code com.aerospike.client.AerospikeClient}.
 *
 * <p>Matches methods like {@code get(BatchPolicy, Key[])},
 * {@code exists(BatchPolicy, Key[])}, {@code getHeader(BatchPolicy, Key[])}.
 * These take {@code Key[]} as the second argument (index 1).
 *
 * <p>Applications using {@code bulkGetBins()} delegate to
 * {@code AerospikeClient.get(BatchPolicy, Key[], String...)}.
 */
public class AerospikeBatchAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Origin("#m") String methodName,
            @Advice.Argument(1) Object keys,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        // keys is Key[] but typed as Object to avoid compile-time dependency
        span = AerospikeClientHelper.startBatchSpan(
                methodName.toUpperCase(),
                (com.aerospike.client.Key[]) keys);
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
