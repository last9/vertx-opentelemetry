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
 * </ul>
 *
 * <h2>Request body (POST/PUT routes)</h2>
 * <p>{@code TracedRouter} does <strong>not</strong> buffer the request body. Add
 * {@code BodyHandler} to individual routes that need it:
 * <pre>{@code
 * router.post("/v1/items")
 *     .handler(BodyHandler.create())   // buffers the body
 *     .handler(ctx -> {
 *         JsonObject body = ctx.getBodyAsJson();
 *         Span span = ctx.get("otel.span");  // use ctx, not Span.current()
 *         ...
 *     });
 * }</pre>
 *
 * <p>{@code BodyHandler} calls {@code ctx.next()} asynchronously (after the body arrives),
 * so the OTel thread-local scope is no longer active in the route handler. Retrieve the
 * span via {@code ctx.get("otel.span")} instead of {@code Span.current()}. To re-activate
 * the span for outgoing calls or RxJava context propagation:
 * <pre>{@code
 * Span span = ctx.get("otel.span");
 * try (Scope scope = span.makeCurrent()) {
 *     ClientTracing.inject(webClient.get(url)).rxSend().subscribe(...);
 * }
 * }</pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Instead of: Router router = Router.router(vertx);
 * Router router = TracedRouter.create(vertx);
 * }</pre>
 *
 * @see OtelLauncher
 * @see ClientTracing
 */
public final class TracedRouter {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";
    public static final String SPAN_KEY = "otel.span";
    private static final String ROUTE_KEY = "otel.route";

    /** Tracks which Router instances already have tracing handlers installed.
     *  Weak references allow GC of Routers that go out of scope. */
    private static final Set<Router> INSTRUMENTED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private static final TextMapGetter<HttpServerRequest> HEADER_GETTER = new TextMapGetter<HttpServerRequest>() {
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

        // High-priority handler: create or adopt spans for every request
        router.route().order(-1000).handler(ctx -> {
            HttpServerRequest request = ctx.request();
            String method = request.method().name();
            String path = request.path();

            // Check if HttpServerAdvice already created a SERVER span for this request.
            // If so, adopt it (add route info) instead of creating a duplicate.
            Span existingSpan = Span.current();
            boolean spanFromHttpServer = existingSpan.getSpanContext().isValid();

            Span span;
            Context otelContext;

            if (spanFromHttpServer) {
                // HttpServerAdvice already created the SERVER span — adopt it.
                // We just need to add route-pattern info via headersEndHandler.
                span = existingSpan;
                otelContext = Context.current();
            } else {
                // No existing span — create a new SERVER span (standalone Router usage
                // without HttpServerAdvice, e.g., manual TracedRouter.create()).
                Context parentContext = propagator.extract(Context.root(), request, HEADER_GETTER);

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

                span = tracer.spanBuilder(method + " " + path)
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

                otelContext = parentContext.with(span);

                // End span when response body is fully sent (only for spans we created)
                ctx.response().bodyEndHandler(v -> span.end());
            }

            ctx.put(SPAN_KEY, span);

            // Update span with route pattern and response details when headers are sent.
            // This runs for both adopted and created spans — adding route info either way.
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

                // --- HTTP body capture: request body only (if BodyHandler used) ---
                if (BodyCaptureConfig.enabled()) {
                    String contentType = ctx.request().getHeader("content-type");
                    if (ctx.getBody() != null && isTextOrJson(contentType)) {
                        String reqBody = trunc(ctx.getBody().toString("UTF-8"));
                        span.setAttribute("http.request.body", reqBody);
                    }
                    // Response body capture would require custom buffering, not supported by default
                }
            });
            // --- END BODY CAPTURE ---


            // Make the OTel context current and proceed to the next handler.
            try (Scope ignored = otelContext.makeCurrent()) {
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

    // Helper for simple content-type filtering for body capture
    private static boolean isTextOrJson(String contentType) {
        return contentType != null &&
               (contentType.startsWith("text/") || contentType.contains("json"));
    }

    private static String trunc(String s) {
        if (s == null) return null;
        int limit = BodyCaptureConfig.maxBytes();
        return s.length() > limit ? s.substring(0, limit) : s;
    }
}

