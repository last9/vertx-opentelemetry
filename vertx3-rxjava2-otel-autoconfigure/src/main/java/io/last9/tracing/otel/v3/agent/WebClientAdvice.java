package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy advice for {@code WebClient.create(Vertx)} and
 * {@code WebClient.create(Vertx, WebClientOptions)}.
 *
 * <p>After the original factory method returns a plain WebClient, this advice
 * wraps it with TracedWebClient so every outgoing HTTP request
 * automatically gets a CLIENT span and {@code traceparent} header injection.
 *
 * <p>Uses Object typing to avoid loading WebClient/TracedWebClient during
 * class transformation (which would cause LinkageError).
 */
public class WebClientAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(@Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object client) {
        client = WebClientAdviceHelper.maybeWrap(client);
    }
}
