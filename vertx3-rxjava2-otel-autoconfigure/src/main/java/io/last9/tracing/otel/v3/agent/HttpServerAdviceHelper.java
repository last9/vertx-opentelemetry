package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Helper for {@link HttpServerAdvice}. Wraps an HTTP request handler with
 * OpenTelemetry SERVER span creation.
 *
 * <p>This operates at the {@code HttpServer.requestHandler()} level — below the
 * Router. Every incoming HTTP request gets a SERVER span, regardless of whether
 * a Vert.x Router is used. If the Router also creates a SERVER span (via
 * {@link io.last9.tracing.otel.v3.TracedRouter}), the Router's handler detects
 * the existing span and updates it (adds route pattern) instead of creating a
 * duplicate.
 */
public final class HttpServerAdviceHelper {

    private static final Logger log = LoggerFactory.getLogger(HttpServerAdviceHelper.class);
    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    /**
     * Track wrapped handlers to avoid double-wrapping when requestHandler()
     * is called multiple times with the same handler.
     */
    private static final Set<Handler<?>> WRAPPED = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<Handler<?>, Boolean>()));

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

    private HttpServerAdviceHelper() {}

    /** Reset state for testing. */
    static void resetForTest() {
        WRAPPED.clear();
    }

    /**
     * Wraps the given handler (if it's a Handler) with SERVER span creation.
     * Returns the original handler if it's already wrapped or not a Handler.
     */
    @SuppressWarnings("unchecked")
    public static Object wrapHandler(Object handler) {
        if (handler == null) {
            return null;
        }
        if (!(handler instanceof Handler)) {
            return handler;
        }
        Handler<HttpServerRequest> original = (Handler<HttpServerRequest>) handler;
        if (!WRAPPED.add(original)) {
            // Already wrapped
            return handler;
        }

        log.info("HttpServerAdviceHelper: wrapping requestHandler with SERVER span creation");

        // Capture tracer and propagator once at wrap time (server startup), not per-request.
        final Tracer tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME);
        final TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();

        Handler<HttpServerRequest> wrapped = new Handler<HttpServerRequest>() {
            @Override
            public void handle(HttpServerRequest request) {

                String method = request.method().name();
                String path = request.path();

                // Extract parent context from incoming traceparent header.
                // Use Context.root() to avoid inheriting stale spans from the event loop thread.
                Context parentContext = propagator.extract(Context.root(), request, HEADER_GETTER);

                // Parse host header for server.address and server.port
                String hostHeader = request.host();
                String serverAddr = hostHeader;
                long serverPort = -1;
                if (hostHeader != null && hostHeader.contains(":")) {
                    int idx = hostHeader.lastIndexOf(':');
                    serverAddr = hostHeader.substring(0, idx);
                    try {
                        serverPort = Long.parseLong(hostHeader.substring(idx + 1));
                    } catch (NumberFormatException ignored) {
                        // keep -1
                    }
                }

                Span span = tracer.spanBuilder(method + " " + path)
                        .setParent(parentContext)
                        .setSpanKind(SpanKind.SERVER)
                        .setAttribute("http.request.method", method)
                        .setAttribute("url.path", path)
                        .setAttribute("url.scheme",
                                request.isSSL() ? "https" : "http")
                        .setAttribute("server.address", serverAddr)
                        .startSpan();

                if (serverPort > 0) {
                    span.setAttribute(AttributeKey.longKey("server.port"), serverPort);
                }

                // Set response attributes and end span when body is fully sent.
                // The span is NOT ended here — it's ended in bodyEndHandler after the
                // response is fully sent. But the SCOPE must be closed immediately after
                // the handler returns to avoid context leaks on the event loop thread.
                HttpServerResponse response = request.response();
                response.headersEndHandler(v -> {
                    int statusCode = response.getStatusCode();
                    span.setAttribute(AttributeKey.longKey("http.response.status_code"), (long) statusCode);
                    if (statusCode >= 500) {
                        span.setStatus(StatusCode.ERROR);
                    }
                });

                response.bodyEndHandler(v -> span.end());

                // Handle connection reset / close before response
                response.closeHandler(v -> {
                    if (!response.ended()) {
                        span.setStatus(StatusCode.ERROR, "Connection closed before response completed");
                        span.end();
                    }
                });

                // Make span current, delegate to original handler, then close scope.
                // CRITICAL: scope must close when handle() returns — NOT in bodyEndHandler.
                // Vert.x runs all requests on a single event loop thread. If the scope
                // stays open after handle() returns, the next request on the same thread
                // inherits a stale scope, causing context confusion and orphan spans.
                Context otelContext = parentContext.with(span);
                try (Scope ignored = otelContext.makeCurrent()) {
                    original.handle(request);
                }
            }
        };

        // Track the wrapped handler too so re-wrapping is prevented
        WRAPPED.add(wrapped);
        return wrapped;
    }
}
