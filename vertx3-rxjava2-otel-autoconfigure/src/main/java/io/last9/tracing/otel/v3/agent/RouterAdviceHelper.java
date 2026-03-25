package io.last9.tracing.otel.v3.agent;

import io.last9.tracing.otel.v3.TracedRouter;
import io.vertx.reactivex.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper for {@link RouterAdvice}. Separated to avoid loading Router/TracedRouter
 * during ByteBuddy class transformation (which would cause LinkageError).
 */
public final class RouterAdviceHelper {

    private static final Logger log = LoggerFactory.getLogger(RouterAdviceHelper.class);

    private RouterAdviceHelper() {}

    /**
     * Installs tracing handlers on the given Router if not already instrumented.
     */
    public static void instrumentIfNeeded(Object router) {
        try {
            if (router instanceof Router) {
                TracedRouter.instrumentExisting((Router) router);
            }
        } catch (Throwable t) {
            log.warn("RouterAdviceHelper: failed to instrument Router — route-pattern "
                    + "span naming will NOT work. Cause: {} ({})", t.getMessage(), t.getClass().getName());
        }
    }
}
