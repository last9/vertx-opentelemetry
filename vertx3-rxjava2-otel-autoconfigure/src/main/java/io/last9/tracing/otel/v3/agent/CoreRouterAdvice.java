package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy advice for core {@code io.vertx.ext.web.Router.router(Vertx)}.
 *
 * <p>Counterpart of {@link RouterAdvice} for applications that use the core Vert.x Router
 * instead of the RxJava2 wrapper.
 */
public class CoreRouterAdvice {

    @Advice.OnMethodExit
    static void onExit(@Advice.Return(typing = Assigner.Typing.DYNAMIC) Object router) {
        try {
            System.err.println("[Last9 OTel Agent] CoreRouterAdvice.onExit fired — router: "
                    + (router != null ? router.getClass().getName() : "null"));
            CoreRouterAdviceHelper.instrumentIfNeeded(router);
        } catch (Throwable t) {
            System.err.println("[Last9 OTel Agent] CoreRouterAdvice.onExit FAILED: "
                    + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }
}
