package io.vertx.tracing.otel;

import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.ext.web.Router;

/**
 * Factory for creating a Vert.x Router with automatic OpenTelemetry span name updates.
 *
 * <p>This is a drop-in replacement for {@code Router.router(vertx)} that automatically
 * registers {@link SpanNameUpdater} on the router. HTTP spans will include the matched
 * route pattern (e.g., {@code GET /v1/users/:id}) instead of just the HTTP method.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Instead of: Router router = Router.router(vertx);
 * Router router = TracedRouter.create(vertx);
 *
 * router.get("/v1/users/:id").handler(ctx -> { ... });
 * }</pre>
 *
 * @see SpanNameUpdater
 * @see OtelLauncher
 */
public final class TracedRouter {

    private TracedRouter() {
        // Factory class
    }

    /**
     * Create a new Router with automatic span name updates.
     *
     * <p>Equivalent to:
     * <pre>{@code
     * Router router = Router.router(vertx);
     * SpanNameUpdater.addToAllRoutes(router);
     * }</pre>
     *
     * @param vertx the Vert.x instance
     * @return a Router with span name updating enabled
     */
    public static Router create(Vertx vertx) {
        Router router = Router.router(vertx);
        SpanNameUpdater.addToAllRoutes(router);
        return router;
    }
}
