package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.semconv.SemanticAttributes;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

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
            Collections.newSetFromMap(new WeakHashMap<Handler<?>, Boolean>()));

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

        log.debug("HttpServerAdviceHelper: wrapping requestHandler with SERVER span creation");

        Handler<HttpServerRequest> wrapped = new Handler<HttpServerRequest>() {
            @Override
            public void handle(HttpServerRequest request) {
                Tracer tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME);
                TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();

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
                        .setAttribute(SemanticAttributes.HTTP_REQUEST_METHOD, method)
                        .setAttribute(SemanticAttributes.URL_PATH, path)
                        .setAttribute(SemanticAttributes.URL_SCHEME,
                                request.isSSL() ? "https" : "http")
                        .setAttribute(SemanticAttributes.SERVER_ADDRESS, serverAddr)
                        .startSpan();

                if (serverPort > 0) {
                    span.setAttribute(SemanticAttributes.SERVER_PORT, serverPort);
                }

                // Store span on request for later retrieval (route pattern update, etc.)
                request.localAddress(); // ensure request is valid
                Scope scope = span.makeCurrent();

                // Set response attributes and end span when body is fully sent
                HttpServerResponse response = request.response();
                response.headersEndHandler(v -> {
                    int statusCode = response.getStatusCode();
                    span.setAttribute(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE, (long) statusCode);
                    if (statusCode >= 500) {
                        span.setStatus(StatusCode.ERROR);
                    }
                });

                response.bodyEndHandler(v -> {
                    span.end();
                    scope.close();
                });

                // Handle connection reset / close before response
                response.closeHandler(v -> {
                    if (!response.ended()) {
                        span.setStatus(StatusCode.ERROR, "Connection closed before response completed");
                        span.end();
                        scope.close();
                    }
                });

                // Delegate to the original handler
                original.handle(request);
            }
        };

        // Track the wrapped handler too so re-wrapping is prevented
        WRAPPED.add(wrapped);
        return wrapped;
    }
}
