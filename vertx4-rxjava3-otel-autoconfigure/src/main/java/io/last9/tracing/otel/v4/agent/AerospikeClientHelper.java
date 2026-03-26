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
        IN_AEROSPIKE_CALL.set(true);

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
     * Starts a span by scanning method arguments for Key or Key[].
     */
    public static Span startSpanFromArgs(String operation, Object[] args) {
        if (args == null) return startSpan(operation, null);
        for (Object arg : args) {
            if (arg instanceof Key) {
                return startSpan(operation, (Key) arg);
            }
            if (arg instanceof Key[]) {
                return startBatchSpan(operation, (Key[]) arg);
            }
        }
        return startSpan(operation, null);
    }

    /**
     * Enriches the current span with Aerospike connection metadata.
     */
    public static void enrichWithConnectionMetadata(Object nodeObj, Object partitionObj) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) return;

        try {
            java.lang.reflect.Method getHost = nodeObj.getClass().getMethod("getHost");
            Object host = getHost.invoke(nodeObj);
            if (host != null) {
                java.lang.reflect.Field nameField = host.getClass().getField("name");
                java.lang.reflect.Field portField = host.getClass().getField("port");
                span.setAttribute("net.peer.name", (String) nameField.get(host));
                span.setAttribute(AttributeKey.longKey("net.peer.port"), (long) portField.getInt(host));
            }
        } catch (Exception ignored) {}

        try {
            java.lang.reflect.Field nsField = findField(partitionObj.getClass(), "namespace");
            if (nsField != null) {
                nsField.setAccessible(true);
                Object ns = nsField.get(partitionObj);
                if (ns instanceof String) {
                    span.setAttribute("db.name", (String) ns);
                }
            }
        } catch (Exception ignored) {}
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Wraps an async Aerospike listener with span lifecycle management.
     */
    public static Object wrapAsyncListener(Object listener, Span span) {
        if (listener == null) return null;
        Class<?>[] interfaces = listener.getClass().getInterfaces();
        if (interfaces.length == 0) {
            interfaces = listener.getClass().getSuperclass() != null
                    ? listener.getClass().getSuperclass().getInterfaces()
                    : new Class<?>[0];
        }
        if (interfaces.length == 0) return listener;

        final Span capturedSpan = span;
        return java.lang.reflect.Proxy.newProxyInstance(
                listener.getClass().getClassLoader(),
                interfaces,
                (proxy, method, args) -> {
                    try {
                        return method.invoke(listener, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    } finally {
                        String methodName = method.getName();
                        if ("onSuccess".equals(methodName)) {
                            capturedSpan.end();
                            IN_AEROSPIKE_CALL.set(false);
                        } else if ("onFailure".equals(methodName)) {
                            if (args != null && args.length > 0 && args[0] instanceof Throwable) {
                                Throwable t = (Throwable) args[0];
                                capturedSpan.recordException(t,
                                        Attributes.of(AttributeKey.booleanKey("exception.escaped"), true));
                                capturedSpan.setStatus(StatusCode.ERROR, t.getMessage());
                            }
                            capturedSpan.end();
                            IN_AEROSPIKE_CALL.set(false);
                        }
                    }
                });
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
