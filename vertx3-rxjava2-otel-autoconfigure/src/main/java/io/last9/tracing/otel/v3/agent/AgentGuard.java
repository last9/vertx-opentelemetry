package io.last9.tracing.otel.v3.agent;

/**
 * Shared ThreadLocal guard to prevent double-instrumentation when the user
 * already uses {@code Traced*} wrappers (which go through {@code DbTracing})
 * AND the bytecode agent intercepts the underlying raw client call.
 *
 * <p>Set to {@code true} in {@code DbTracing} around the delegate supplier call.
 * Checked in advice helpers (Redis, Aerospike, JDBC, reactive SQL) to skip
 * span creation if already inside a traced operation.
 */
public final class AgentGuard {

    /** True when inside a {@code DbTracing.traceSync/traceSingle/traceMaybe/traceCompletable} call. */
    public static final ThreadLocal<Boolean> IN_DB_TRACED_CALL =
            ThreadLocal.withInitial(() -> false);

    private AgentGuard() {}
}
