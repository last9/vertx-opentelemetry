package io.last9.tracing.otel.v4;

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
 * This utility updates the span name to include the route pattern (GET /v1/users/:id).
 *
 * <h2>Usage Option 1: Use TracedRouter (recommended)</h2>
 * <pre>{@code
 * Router router = TracedRouter.create(vertx);
 * }</pre>
 *
 * <h2>Usage Option 2: Add to individual routes</h2>
 * <pre>{@code
 * router.get("/v1/holding")
 *     .handler(SpanNameUpdater::updateSpanName)
 *     .handler(myHandler);
 * }</pre>
 *
 * @see TracedRouter
 * @see OtelLauncher
 */
public class SpanNameUpdater {

    private static final String SPAN_KEY = "otel.span";
    private static final String ROUTE_KEY = "otel.route";

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

            span.updateName(method + " " + route);
            span.setAttribute(SemanticAttributes.HTTP_ROUTE, route);
        }
        ctx.next();
    }

    /**
     * Add span name updater to all routes in the router.
     * Can be called before or after defining routes.
     *
     * <p>This works in two phases:
     * <ol>
     *   <li>A high-priority global handler captures the span reference and stores
     *       the route path after each handler in the chain</li>
     *   <li>A response head-end handler updates the span name using the captured
     *       route path before the response is fully sent</li>
     * </ol>
     *
     * @param router the Vert.x router
     */
    public static void addToAllRoutes(Router router) {
        router.route().order(-1000).handler(ctx -> {
            // Capture span while it's still active — vertx-opentelemetry will
            // end the span when the response completes, so we must grab it now.
            Span span = Span.current();
            if (span != null && span.isRecording()) {
                ctx.put(SPAN_KEY, span);
            }

            // Use headersEndHandler which fires BEFORE the response body is sent
            // and before vertx-opentelemetry's sendResponse ends the span.
            ctx.response().headersEndHandler(v -> {
                Span captured = ctx.get(SPAN_KEY);
                if (captured != null && captured.isRecording()) {
                    String method = ctx.request().method().name();

                    // Prefer the stored route path (set by route-matched handler)
                    String route = ctx.get(ROUTE_KEY);
                    if (route == null) {
                        route = getRoutePath(ctx);
                    }

                    captured.updateName(method + " " + route);
                    captured.setAttribute(SemanticAttributes.HTTP_ROUTE, route);

                    int statusCode = ctx.response().getStatusCode();
                    captured.setAttribute(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE, statusCode);
                    if (statusCode >= 400) {
                        captured.setStatus(StatusCode.ERROR);
                    }
                }
            });

            ctx.next();
        });

        // Also register a lower-priority handler that captures the matched route
        // path. This runs AFTER route matching, so currentRoute() returns the
        // actual matched route (not our catch-all).
        router.route().order(Integer.MAX_VALUE - 1).handler(ctx -> {
            Route currentRoute = ctx.currentRoute();
            if (currentRoute != null) {
                String path = currentRoute.getPath();
                if (path != null && !path.isEmpty()) {
                    ctx.put(ROUTE_KEY, path);
                }
            }
            ctx.next();
        });
    }

    private static String getRoutePath(RoutingContext ctx) {
        Route currentRoute = ctx.currentRoute();
        if (currentRoute != null) {
            String path = currentRoute.getPath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
        }

        String path = ctx.normalizedPath();
        if (path != null && !path.isEmpty()) {
            return path;
        }

        return ctx.request().path();
    }
}
