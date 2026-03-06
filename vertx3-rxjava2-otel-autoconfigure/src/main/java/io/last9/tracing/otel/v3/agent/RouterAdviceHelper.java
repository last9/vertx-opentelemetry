package io.last9.tracing.otel.v3.agent;

import io.last9.tracing.otel.v3.TracedRouter;
import io.vertx.reactivex.ext.web.Router;

/**
 * Helper for {@link RouterAdvice}. Separated to avoid loading Router/TracedRouter
 * during ByteBuddy class transformation (which would cause LinkageError).
 */
public final class RouterAdviceHelper {

    private RouterAdviceHelper() {}

    /**
     * Installs tracing handlers on the given Router if not already instrumented.
     */
    public static void instrumentIfNeeded(Object router) {
        if (router instanceof Router) {
            TracedRouter.instrumentExisting((Router) router);
        }
    }
}
