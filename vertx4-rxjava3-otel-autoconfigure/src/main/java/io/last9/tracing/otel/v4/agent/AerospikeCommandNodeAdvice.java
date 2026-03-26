package io.last9.tracing.otel.v4.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code com.aerospike.client.command.Command.getNode()}.
 *
 * <p>Enriches the active Aerospike CLIENT span with connection metadata:
 * {@code net.peer.name}, {@code net.peer.port}, and {@code db.name} (namespace).
 *
 * <p>This fires during command execution, after the cluster has resolved which
 * node handles the request — the only point where host/port are available.
 */
public class AerospikeCommandNodeAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(
            @Advice.Return Object node,
            @Advice.Argument(1) Object partition) {
        AerospikeClientHelper.enrichWithConnectionMetadata(node, partition);
    }
}
