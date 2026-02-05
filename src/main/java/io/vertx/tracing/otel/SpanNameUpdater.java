package io.vertx.tracing.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.semconv.SemanticAttributes;
import io.vertx.rxjava3.ext.web.Route;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.RoutingContext;

/**
 * Utility to update OpenTelemetry span names with the actual route pattern.
 *
 * <p>By default, Vert.x creates HTTP spans with just the method name (GET, POST).
 * This utility updates the span name to include the route pattern (GET /v1/holding).
 *
 * <h2>Usage Option 1: Add to individual routes</h2>
 * <pre>{@code
 * router.get("/v1/holding")
 *     .handler(SpanNameUpdater::updateSpanName)
 *     .handler(myHandler);
 * }</pre>
 *
 * <h2>Usage Option 2: Add to all routes (recommended)</h2>
 * <pre>{@code
 * // After defining all routes
 * SpanNameUpdater.addToAllRoutes(router);
 * }</pre>
 *
 * @see OtelLauncher
 */
public class SpanNameUpdater {

    private SpanNameUpdater() {
        // Utility class
    }

    /**
     * Handler that updates the current span name to include the HTTP method and route.
     * Call this at the start of your route handler chain.
     *
     * @param ctx the routing context
     */
    public static void updateSpanName(RoutingContext ctx) {
        Span span = Span.current();
        if (span != null && span.isRecording()) {
            String method = ctx.request().method().name();
            String route = getRoutePath(ctx);

            // Update span name to "GET /v1/holding" format
            span.updateName(method + " " + route);

            // Also set the http.route attribute for better filtering
            span.setAttribute(SemanticAttributes.HTTP_ROUTE, route);
        }
        ctx.next();
    }

    /**
     * Add span name updater to all routes in the router.
     * Call this after defining all your routes.
     *
     * <p>This adds a global handler with high priority that updates the span name
     * after the response is complete, ensuring the correct route is captured.
     *
     * @param router the Vert.x router
     */
    public static void addToAllRoutes(Router router) {
        // Add a global handler that runs first
        router.route().order(-1000).handler(ctx -> {
            // Defer span name update until after route matching and response
            ctx.addEndHandler(v -> {
                Span span = Span.current();
                if (span != null && span.isRecording()) {
                    String method = ctx.request().method().name();
                    String route = getRoutePath(ctx);
                    span.updateName(method + " " + route);
                    span.setAttribute(SemanticAttributes.HTTP_ROUTE, route);

                    // Set status code
                    int statusCode = ctx.response().getStatusCode();
                    span.setAttribute(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE, statusCode);
                    if (statusCode >= 400) {
                        span.setStatus(StatusCode.ERROR);
                    }
                }
            });
            ctx.next();
        });
    }

    private static String getRoutePath(RoutingContext ctx) {
        // Try to get the matched route path
        Route currentRoute = ctx.currentRoute();
        if (currentRoute != null) {
            String path = currentRoute.getPath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
        }

        // Fall back to the normalized path
        String path = ctx.normalizedPath();
        if (path != null && !path.isEmpty()) {
            return path;
        }

        // Last resort - use the request path
        return ctx.request().path();
    }
}
