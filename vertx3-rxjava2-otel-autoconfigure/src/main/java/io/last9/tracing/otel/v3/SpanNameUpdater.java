package io.last9.tracing.otel.v3;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.semconv.SemanticAttributes;
import io.vertx.reactivex.ext.web.Route;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;

/**
 * Utility to update OpenTelemetry span names with the actual route pattern.
 *
 * <p>By default, spans may only contain the HTTP method and raw path.
 * This utility updates the span name to include the route pattern (GET /v1/users/:id).
 *
 * <p><strong>Note:</strong> In Vert.x 3, use {@link TracedRouter#create} for full
 * tracing support. This class is useful only when you need to update span names on
 * spans you create manually (Vert.x 3 has no built-in tracing SPI).
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
            Span span = Span.current();
            if (span != null && span.isRecording()) {
                ctx.put(SPAN_KEY, span);
            }

            ctx.response().headersEndHandler(v -> {
                Span captured = ctx.get(SPAN_KEY);
                if (captured != null && captured.isRecording()) {
                    String method = ctx.request().method().name();

                    String route = ctx.get(ROUTE_KEY);
                    if (route == null) {
                        route = getRoutePath(ctx);
                    }

                    captured.updateName(method + " " + route);
                    captured.setAttribute(SemanticAttributes.HTTP_ROUTE, route);

                    int statusCode = ctx.response().getStatusCode();
                    captured.setAttribute(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE, (long) statusCode);
                    if (statusCode >= 400) {
                        captured.setStatus(StatusCode.ERROR);
                    }
                }
            });

            ctx.next();
        });

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

        String path = ctx.normalisedPath();
        if (path != null && !path.isEmpty()) {
            return path;
        }

        return ctx.request().path();
    }
}
