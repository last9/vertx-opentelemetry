package io.last9.tracing.otel.v4.agent;

import com.aerospike.client.Key;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;


/**
 * Helper methods called by {@link AerospikeClientAdvice} to create CLIENT spans
 * for Aerospike data-plane operations.
 *
 * <p>Intercepts at the raw {@code com.aerospike.client.AerospikeClient} level,
 * covering direct usage without requiring the {@code TracedAerospikeClient} wrapper.
 *
 * <p>Uses a ThreadLocal guard to prevent double spans when the user is already
 * using {@code TracedAerospikeClient} (which delegates to the raw client).
 */
public final class AerospikeClientHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v4";

    /**
     * Guard to prevent double spans when both AerospikeClientAdvice (on the public API)
     * and AerospikeSyncCommandAdvice (on the internal command) fire for the same call.
     * The first advice to enter sets this to true; the second sees it and skips.
     */
    static final ThreadLocal<Boolean> IN_AEROSPIKE_CALL =
            ThreadLocal.withInitial(() -> false);

    private AerospikeClientHelper() {}

    /**
     * Starts a CLIENT span for the given Aerospike operation.
     * Returns null if already inside a traced call (idempotency guard).
     *
     * @param operation the operation name (e.g., "GET", "PUT", "DELETE")
     * @param key       the Aerospike Key (nullable for batch ops with Key[])
     * @return the span, or null if suppressed
     */
    public static Span startSpan(String operation, Key key) {
        if (AgentGuard.IN_DB_TRACED_CALL.get() || IN_AEROSPIKE_CALL.get()) {
            return null;
        }
        IN_AEROSPIKE_CALL.set(true);

        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        String spanName;
        String dbNamespace = null;
        if (key != null) {
            spanName = "aerospike " + operation + " " + key.namespace + "." + key.setName;
            dbNamespace = key.namespace;
        } else {
            spanName = "aerospike " + operation;
        }

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system", "aerospike")
                .setAttribute("db.statement", operation)
                .startSpan();

        if (dbNamespace != null) {
            span.setAttribute("db.name", dbNamespace);
        }

        return span;
    }

    /**
     * Starts a CLIENT span for batch Aerospike operations.
     */
    public static Span startBatchSpan(String operation, Key[] keys) {
        if (AgentGuard.IN_DB_TRACED_CALL.get() || IN_AEROSPIKE_CALL.get()) {
            return null;
        }

        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        String spanName = "aerospike " + operation
                + " (" + (keys != null ? keys.length : 0) + " keys)";
        String dbNamespace = (keys != null && keys.length > 0) ? keys[0].namespace : null;

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system", "aerospike")
                .setAttribute("db.statement", operation)
                .startSpan();

        if (dbNamespace != null) {
            span.setAttribute("db.name", dbNamespace);
        }

        return span;
    }

    /**
     * Ends the span (success or error). Closes the scope if provided.
     */
    public static void endSpan(Span span, Scope scope, Throwable thrown) {
        if (span == null) return;
        try {
            if (thrown != null) {
                span.recordException(thrown,
                        Attributes.of(AttributeKey.booleanKey("exception.escaped"), true));
                span.setStatus(StatusCode.ERROR, thrown.getMessage());
            }
        } finally {
            IN_AEROSPIKE_CALL.set(false);
            if (scope != null) {
                scope.close();
            }
            span.end();
        }
    }
}
