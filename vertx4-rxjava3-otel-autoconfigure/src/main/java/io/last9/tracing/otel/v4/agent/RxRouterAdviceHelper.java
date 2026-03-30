package io.last9.tracing.otel.v4.agent;

import io.last9.tracing.otel.v4.SpanNameUpdater;
import io.vertx.rxjava3.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Helper for {@link RxRouterAdvice}. Calls {@link SpanNameUpdater#addToAllRoutes(Router)}
 * (or the core-router variant) on the newly created Router, with deduplication to avoid
 * double-instrumentation.
 *
 * <p>Handles three Router variants:
 * <ol>
 *   <li>{@code io.vertx.rxjava3.ext.web.Router} — RxJava 3 (primary v4 target)</li>
 *   <li>{@code io.vertx.ext.web.Router} — core Router, also used when the app uses the
 *       RxJava 2 bindings ({@code io.vertx.reactivex.ext.web.Router}) which delegate
 *       internally to the core router</li>
 * </ol>
 */
public final class RxRouterAdviceHelper {

    private static final Logger log = LoggerFactory.getLogger(RxRouterAdviceHelper.class);
    private static final Set<Object> INSTRUMENTED = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    private RxRouterAdviceHelper() {}

    public static void instrumentIfNeeded(Object routerObj) {
        // Deduplicate by object identity — each Router instance is instrumented at most once
        if (!INSTRUMENTED.add(routerObj)) {
            return;
        }

        if (routerObj instanceof Router) {
            try {
                SpanNameUpdater.addToAllRoutes((Router) routerObj);
                log.info("Vertx4Agent: SpanNameUpdater installed on RxJava3 Router");
            } catch (Throwable t) {
                log.warn("Vertx4Agent: Failed to install SpanNameUpdater on RxJava3 Router: {}", t.getMessage());
            }
        } else if (routerObj instanceof io.vertx.ext.web.Router) {
            try {
                SpanNameUpdater.addToAllRoutesCoreRouter((io.vertx.ext.web.Router) routerObj);
                log.info("Vertx4Agent: SpanNameUpdater installed on core Router (RxJava2/plain)");
            } catch (Throwable t) {
                log.warn("Vertx4Agent: Failed to install SpanNameUpdater on core Router: {}", t.getMessage());
            }
        }
    }
}
