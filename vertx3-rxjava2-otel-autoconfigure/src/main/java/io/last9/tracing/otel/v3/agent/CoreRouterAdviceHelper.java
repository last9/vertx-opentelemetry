package io.last9.tracing.otel.v3.agent;

import io.last9.tracing.otel.v3.CoreTracedRouter;
import io.vertx.ext.web.Router;

/**
 * Helper for {@link CoreRouterAdvice}. Separated to avoid loading Router/CoreTracedRouter
 * during ByteBuddy class transformation (which would cause LinkageError).
 */
public final class CoreRouterAdviceHelper {

    private CoreRouterAdviceHelper() {}

    public static void instrumentIfNeeded(Object router) {
        if (router instanceof Router) {
            CoreTracedRouter.instrumentExisting((Router) router);
        }
    }
}
