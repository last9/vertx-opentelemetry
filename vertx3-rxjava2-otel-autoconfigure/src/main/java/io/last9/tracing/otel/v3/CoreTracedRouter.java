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
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Factory for creating a core Vert.x 3 Router with automatic OpenTelemetry tracing.
 *
 * <p>This is the core-API counterpart of {@link TracedRouter}, for applications that use
 * {@code io.vertx.ext.web.Router} directly instead of the RxJava2 wrapper
 * {@code io.vertx.reactivex.ext.web.Router}.
 *
 * @see TracedRouter
 */
public final class CoreTracedRouter {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";
    private static final String SPAN_KEY = "otel.span";
    private static final String ROUTE_KEY = "otel.route";

    /** Shared set of core Router instances that already have tracing handlers.
     *  Package-private so that {@link TracedRouter} can register its underlying core
     *  Router to prevent double instrumentation when both advices fire. */
    static final Set<Router> INSTRUMENTED = Collections.synchronizedSet(
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

    private CoreTracedRouter() {}

    public static Router create(Vertx vertx) {
        return create(vertx, GlobalOpenTelemetry.get());
    }

    public static Router create(Vertx vertx, OpenTelemetry openTelemetry) {
        Router router = Router.router(vertx);
        instrumentExisting(router, openTelemetry);
        return router;
    }

    public static void instrumentExisting(Router router) {
        instrumentExisting(router, GlobalOpenTelemetry.get());
    }

    public static void instrumentExisting(Router router, OpenTelemetry openTelemetry) {
        if (!INSTRUMENTED.add(router)) {
            return;
        }
        installTracingHandler(router, openTelemetry);
    }

    private static void installTracingHandler(Router router, OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        TextMapPropagator propagator = openTelemetry.getPropagators().getTextMapPropagator();

        router.route().order(-1000).handler(ctx -> {
            HttpServerRequest request = ctx.request();
            String method = request.method().name();
            String path = request.path();

            // Use Context.root() to avoid context leak on Vert.x event loop
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

            ctx.response().bodyEndHandler(v -> span.end());

            Context otelContext = parentContext.with(span);

            request.bodyHandler(body -> {
                if (body.length() > 0) {
                    ctx.setBody(body);
                }
                try (Scope ignored = otelContext.makeCurrent()) {
                    ctx.next();
                }
            });
        });

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
