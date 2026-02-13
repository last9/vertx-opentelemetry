package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.semconv.SemanticAttributes;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.http.HttpServerRequest;
import io.vertx.reactivex.ext.web.Route;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;

/**
 * Factory for creating a Vert.x 3 Router with automatic OpenTelemetry tracing.
 *
 * <p>Unlike Vert.x 4, Vert.x 3 has no built-in tracing SPI. This class provides
 * handler-based instrumentation that:
 * <ul>
 *   <li>Creates SERVER spans for every incoming HTTP request</li>
 *   <li>Extracts W3C {@code traceparent} headers for distributed tracing</li>
 *   <li>Sets HTTP semantic convention attributes on spans</li>
 *   <li>Updates span names with matched route patterns (e.g., {@code GET /v1/users/:id})</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Instead of: Router router = Router.router(vertx);
 * Router router = TracedRouter.create(vertx);
 *
 * router.get("/v1/users/:id").handler(ctx -> { ... });
 * }</pre>
 *
 * @see OtelLauncher
 */
public final class TracedRouter {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";
    private static final String SPAN_KEY = "otel.span";
    private static final String ROUTE_KEY = "otel.route";

    private static final TextMapGetter<HttpServerRequest> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServerRequest carrier) {
            return carrier.headers().names();
        }

        @Override
        public String get(HttpServerRequest carrier, String key) {
            return carrier.getHeader(key);
        }
    };

    private TracedRouter() {
        // Factory class
    }

    /**
     * Create a new Router with automatic OpenTelemetry tracing.
     *
     * <p>Uses {@link GlobalOpenTelemetry#get()} to obtain the OpenTelemetry instance.
     * Ensure {@link OtelLauncher} or {@link io.last9.tracing.otel.OtelSdkSetup#initialize()}
     * has been called before creating the router.
     *
     * @param vertx the Vert.x instance
     * @return a Router with tracing enabled
     */
    public static Router create(Vertx vertx) {
        return create(vertx, GlobalOpenTelemetry.get());
    }

    /**
     * Create a new Router with automatic OpenTelemetry tracing using the given
     * OpenTelemetry instance.
     *
     * @param vertx the Vert.x instance
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a Router with tracing enabled
     */
    public static Router create(Vertx vertx, OpenTelemetry openTelemetry) {
        Router router = Router.router(vertx);
        installTracingHandler(router, openTelemetry);
        return router;
    }

    private static void installTracingHandler(Router router, OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        TextMapPropagator propagator = openTelemetry.getPropagators().getTextMapPropagator();

        // High-priority handler: create spans for every request
        router.route().order(-1000).handler(ctx -> {
            HttpServerRequest request = ctx.request();
            String method = request.method().name();
            String path = request.path();

            // 1. Extract parent context from incoming W3C traceparent header
            Context parentContext = propagator.extract(Context.current(), request, HEADER_GETTER);

            // 2. Start a SERVER span
            Span span = tracer.spanBuilder(method + " " + path)
                    .setParent(parentContext)
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute(SemanticAttributes.HTTP_REQUEST_METHOD, method)
                    .setAttribute(SemanticAttributes.URL_PATH, path)
                    .setAttribute(SemanticAttributes.SERVER_ADDRESS, request.host())
                    .startSpan();

            ctx.put(SPAN_KEY, span);

            // 3. Update span with response details when headers are sent
            ctx.response().headersEndHandler(v -> {
                String route = ctx.get(ROUTE_KEY);
                if (route == null) {
                    route = getRoutePath(ctx);
                }

                span.updateName(method + " " + route);
                span.setAttribute(SemanticAttributes.HTTP_ROUTE, route);

                int statusCode = ctx.response().getStatusCode();
                span.setAttribute(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE, (long) statusCode);
                if (statusCode >= 500) {
                    span.setStatus(StatusCode.ERROR);
                }
            });

            // 4. End span when response body is fully sent
            ctx.response().bodyEndHandler(v -> span.end());

            // 5. Make span current for synchronous handler chain + RxJava propagation
            try (Scope ignored = span.makeCurrent()) {
                ctx.next();
            }
        });

        // Low-priority handler: capture the matched route path after route matching
        router.route().order(Integer.MAX_VALUE - 1).handler(ctx -> {
            Route currentRoute = ctx.currentRoute();
            if (currentRoute != null) {
                String routePath = currentRoute.getPath();
                if (routePath != null && !routePath.isEmpty()) {
                    ctx.put(ROUTE_KEY, routePath);
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
