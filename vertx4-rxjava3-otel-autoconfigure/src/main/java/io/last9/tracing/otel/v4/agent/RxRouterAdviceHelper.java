package io.last9.tracing.otel.v4.agent;

import io.last9.tracing.otel.v4.SpanNameUpdater;
import io.vertx.rxjava3.ext.web.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper for {@link RxRouterAdvice}. Calls {@link SpanNameUpdater#addToAllRoutes(Router)}
 * on the newly created Router, with deduplication to avoid double-instrumentation.
 */
public final class RxRouterAdviceHelper {

    private static final Logger log = LoggerFactory.getLogger(RxRouterAdviceHelper.class);
    private static final Set<Integer> INSTRUMENTED = Collections.newSetFromMap(
            new ConcurrentHashMap<>());

    private RxRouterAdviceHelper() {}

    public static void instrumentIfNeeded(Object routerObj) {
        if (!(routerObj instanceof Router)) {
            return;
        }
        Router router = (Router) routerObj;

        // Deduplicate by identity hash — each Router instance is instrumented at most once
        if (!INSTRUMENTED.add(System.identityHashCode(router))) {
            return;
        }

        try {
            SpanNameUpdater.addToAllRoutes(router);
            log.info("Vertx4Agent: SpanNameUpdater installed on RxJava3 Router");
        } catch (Throwable t) {
            log.warn("Vertx4Agent: Failed to install SpanNameUpdater on Router: {}", t.getMessage());
        }
    }
}
