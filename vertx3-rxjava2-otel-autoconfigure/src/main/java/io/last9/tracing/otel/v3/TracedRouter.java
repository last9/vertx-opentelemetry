package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.semconv.ExceptionAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.http.HttpServerRequest;
import io.vertx.reactivex.ext.web.Route;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

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
 *   <li>Buffers the request body so {@link RoutingContext#getBodyAsJson()} and
 *       {@link RoutingContext#getBody()} work in handlers without a separate
 *       {@code BodyHandler}</li>
 * </ul>
 *
 * <p><strong>Do not add {@code BodyHandler.create()} to the router.</strong>
 * {@code TracedRouter} buffers the request body itself and calls {@code ctx.next()} only
 * after the body has arrived. Adding a separate {@code BodyHandler} will conflict with
 * this mechanism.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Instead of: Router router = Router.router(vertx);
 * Router router = TracedRouter.create(vertx);
 *
 * // No BodyHandler needed — body is already available:
 * router.post("/v1/items").handler(ctx -> {
 *     JsonObject body = ctx.getBodyAsJson();
 *     ...
 * });
 * }</pre>
 *
 * @see OtelLauncher
 * @see ClientTracing
 */
public final class TracedRouter {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";
    private static final String SPAN_KEY = "otel.span";
    private static final String ROUTE_KEY = "otel.route";

    /** Tracks which Router instances already have tracing handlers installed.
     *  Weak references allow GC of Routers that go out of scope. */
    private static final Set<Router> INSTRUMENTED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

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
        instrumentExisting(router, openTelemetry);
        return router;
    }

    /**
     * Install tracing handlers on an existing Router using {@link GlobalOpenTelemetry}.
     *
     * <p>Called by the bytecode agent ({@link io.last9.tracing.otel.v3.agent.RouterAdvice})
     * after intercepting {@code Router.router(Vertx)}. Safe to call multiple times on the
     * same Router instance — only the first call installs handlers.
     *
     * @param router the Router to instrument
     */
    public static void instrumentExisting(Router router) {
        instrumentExisting(router, GlobalOpenTelemetry.get());
    }

    /**
     * Install tracing handlers on an existing Router. Idempotent — if the Router
     * has already been instrumented (by either this method or {@link #create}),
     * subsequent calls are no-ops.
     *
     * @param router        the Router to instrument
     * @param openTelemetry the OpenTelemetry instance to use
     */
    public static void instrumentExisting(Router router, OpenTelemetry openTelemetry) {
        if (!INSTRUMENTED.add(router)) {
            return;
        }
        // The RxJava2 Router.router(vertx) internally calls core Router.router(vertx.getDelegate()).
        // CoreRouterAdvice fires on that inner call BEFORE RouterAdvice fires on the outer call.
        // If the core Router is already instrumented, skip — the core tracing handler is sufficient.
        // If not yet instrumented, register it to prevent CoreRouterAdvice from adding a second handler.
        if (!CoreTracedRouter.INSTRUMENTED.add(router.getDelegate())) {
            // Core router already has tracing handlers — don't install a second set
            return;
        }
        installTracingHandler(router, openTelemetry);
    }

    private static void installTracingHandler(Router router, OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        TextMapPropagator propagator = openTelemetry.getPropagators().getTextMapPropagator();

        // High-priority handler: create spans for every request
        router.route().order(-1000).handler(ctx -> {
            HttpServerRequest request = ctx.request();
            String method = request.method().name();
            String path = request.path();

            // 1. Extract parent context from incoming W3C traceparent header.
            //    IMPORTANT: Use Context.root() — NOT Context.current() — as the base.
            //    Vert.x 3 runs all handlers on a single event-loop thread. If any prior
            //    request's scope leaked into the thread-local (or a handler forgot to close
            //    a scope), Context.current() would still hold the previous request's span,
            //    causing this new SERVER span to parent under the old one. Using root()
            //    ensures each incoming HTTP request starts a fresh trace (unless the caller
            //    sends a valid traceparent header, which extract() will honour).
            Context parentContext = propagator.extract(Context.root(), request, HEADER_GETTER);

            // 2. Start a SERVER span
            String hostHeader = request.host();
            String serverAddr = hostHeader;
            long serverPort = -1;
            if (hostHeader != null && hostHeader.contains(":")) {
                int idx = hostHeader.lastIndexOf(':');
                serverAddr = hostHeader.substring(0, idx);
                try {
                    serverPort = Long.parseLong(hostHeader.substring(idx + 1));
                } catch (NumberFormatException ignored) {
                    // keep serverPort as -1
                }
            }

            Span span = tracer.spanBuilder(method + " " + path)
                    .setParent(parentContext)
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute(SemanticAttributes.HTTP_REQUEST_METHOD, method)
                    .setAttribute(SemanticAttributes.URL_PATH, path)
                    .setAttribute(SemanticAttributes.URL_SCHEME,
                            request.isSSL() ? "https" : "http")
                    .setAttribute(SemanticAttributes.SERVER_ADDRESS, serverAddr)
                    .startSpan();

            if (serverPort > 0) {
                span.setAttribute(SemanticAttributes.SERVER_PORT, serverPort);
            }

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
                    Throwable failure = ctx.failure();
                    if (failure != null) {
                        span.recordException(failure,
                                Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                    }
                    span.setStatus(StatusCode.ERROR);
                }
            });

            // 4. End span when response body is fully sent
            ctx.response().bodyEndHandler(v -> span.end());

            // 5. Build the OTel context containing the new span
            Context otelContext = parentContext.with(span);

            // 6. Buffer the request body, then advance the handler chain with the span
            //    active in the OTel ThreadLocal. Using bodyHandler (rather than calling
            //    ctx.next() synchronously) is critical: Vert.x 3 always delivers body
            //    data asynchronously via request.endHandler, even for requests with no
            //    body. A synchronous try-with-resources would close the scope before
            //    downstream handlers run, making Span.current() return the root (no-op)
            //    span and leaving trace_id/span_id empty in log MDC.
            request.bodyHandler(body -> {
                if (body.length() > 0) {
                    ctx.setBody(body);
                }
                try (Scope ignored = otelContext.makeCurrent()) {
                    ctx.next();
                }
            });
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
