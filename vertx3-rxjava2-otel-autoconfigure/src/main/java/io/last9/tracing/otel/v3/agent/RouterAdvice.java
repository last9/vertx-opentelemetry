package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy advice for {@code Router.router(Vertx)}.
 *
 * <p>After the original method returns a plain Router, this advice installs
 * OpenTelemetry tracing handlers on it. Uses Object typing to avoid loading
 * Router/TracedRouter during class transformation (which would cause LinkageError).
 */
public class RouterAdvice {

    @Advice.OnMethodExit
    static void onExit(@Advice.Return(typing = Assigner.Typing.DYNAMIC) Object router) {
        try {
            System.err.println("[Last9 OTel Agent] RouterAdvice.onExit fired — router: "
                    + (router != null ? router.getClass().getName() : "null"));
            RouterAdviceHelper.instrumentIfNeeded(router);
        } catch (Throwable t) {
            System.err.println("[Last9 OTel Agent] RouterAdvice.onExit FAILED: "
                    + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }
}
