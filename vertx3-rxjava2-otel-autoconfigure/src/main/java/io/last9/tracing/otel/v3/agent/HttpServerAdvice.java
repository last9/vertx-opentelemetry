package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy advice for {@code HttpServerImpl.requestHandler(Handler)}.
 *
 * <p>Intercepts the handler being set on the HTTP server and wraps it with a
 * span-creating handler that produces SERVER spans for every incoming request.
 * This catches all HTTP requests regardless of whether a Vert.x Router is used.
 *
 * <p>Uses Object typing to avoid loading Vert.x classes during transformation.
 */
public class HttpServerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object handler) {
        handler = HttpServerAdviceHelper.wrapHandler(handler);
    }
}
