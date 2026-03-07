package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.semconv.ExceptionAttributes;
import io.opentelemetry.semconv.SemanticAttributes;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper methods called by {@link NettyHttpClientAdvice} to create CLIENT spans
 * for outgoing HTTP requests through Vert.x's HTTP client (Netty-based).
 *
 * <p>Intercepts at {@code HttpClientRequestImpl.end()} — the common send path
 * for all outgoing HTTP requests in Vert.x. Extracts HTTP method, host, port,
 * and URI from the request object via reflection. Injects {@code traceparent}
 * header for cross-service W3C trace context propagation.
 *
 * <p>Response handling: the span is stored in an in-flight map keyed by request
 * identity. When the response arrives ({@code handleResponse}) or an exception
 * occurs ({@code handleException}), the span is completed with the response
 * status code or error details.
 *
 * <p>Uses the {@link AgentGuard} to prevent double-instrumentation when the user
 * also uses the WebClient (which is already instrumented separately).
 */
public final class NettyHttpClientHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    /** Guard to prevent double-instrumentation with WebClient advice. */
    static final ThreadLocal<Boolean> IN_HTTP_CLIENT_CALL =
            ThreadLocal.withInitial(() -> false);

    /**
     * In-flight spans keyed by {@code System.identityHashCode(request)}.
     * Entries are added in {@link #startSpan} and removed in
     * {@link #handleResponse} or {@link #handleException}.
     */
    private static final ConcurrentHashMap<Integer, Span> IN_FLIGHT = new ConcurrentHashMap<>();

    /** Injects W3C trace context headers into the outgoing HTTP request via reflection. */
    private static final TextMapSetter<Object> HEADER_SETTER = (carrier, key, value) -> {
        try {
            carrier.getClass().getMethod("putHeader", String.class, String.class)
                    .invoke(carrier, key, value);
        } catch (Exception ignored) {
            // Request object may not support putHeader — skip silently
        }
    };

    private NettyHttpClientHelper() {}

    /**
     * Starts a CLIENT span for the given HTTP client request and injects
     * the {@code traceparent} header for distributed tracing.
     * Returns null if already inside a WebClient traced call.
     *
     * @param request the HttpClientRequestImpl instance
     * @return the span, or null if suppressed
     */
    public static Span startSpan(Object request) {
        if (IN_HTTP_CLIENT_CALL.get()) {
            return null;
        }

        String method = extractMethod(request);
        String host = extractHost(request);
        String uri = extractUri(request);
        int port = extractPort(request);

        String spanName = method + " " + (host != null ? host : "unknown");
        Tracer tracer = GlobalOpenTelemetry.get().getTracer(TRACER_NAME);

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SemanticAttributes.HTTP_METHOD, method)
                .setAttribute(SemanticAttributes.NET_PEER_NAME, host != null ? host : "unknown")
                .startSpan();

        if (uri != null) {
            span.setAttribute(SemanticAttributes.HTTP_URL, buildUrl(host, port, uri));
        }
        if (port > 0) {
            span.setAttribute(SemanticAttributes.NET_PEER_PORT, (long) port);
        }

        // Inject traceparent header for cross-service propagation
        Context otelContext = Context.current().with(span);
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .inject(otelContext, request, HEADER_SETTER);

        // Store span for response/exception handling
        IN_FLIGHT.put(System.identityHashCode(request), span);

        IN_HTTP_CLIENT_CALL.set(true);
        return span;
    }

    /**
     * Called from {@code end()} onExit. Closes the scope but does NOT end
     * the span — the span is ended by {@link #handleResponse} or
     * {@link #handleException}. If {@code end()} itself throws, ends the
     * span immediately as a safety net and removes it from IN_FLIGHT.
     *
     * @param request the HttpClientRequestImpl (for IN_FLIGHT map cleanup)
     * @param span    the span created by {@link #startSpan}
     * @param scope   the scope to close
     * @param thrown  non-null if {@code end()} threw an exception
     */
    public static void exitSend(Object request, Span span, Scope scope, Throwable thrown) {
        try {
            if (scope != null) {
                scope.close();
            }
            if (thrown != null && span != null) {
                // end() itself failed — response will never arrive, end span now
                IN_FLIGHT.remove(System.identityHashCode(request));
                span.recordException(thrown,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, thrown.getMessage());
                span.end();
            }
        } finally {
            IN_HTTP_CLIENT_CALL.set(false);
        }
    }

    /**
     * Called when the HTTP response is received. Sets the response status code
     * on the span and ends it.
     *
     * @param request  the HttpClientRequestImpl instance (for map lookup)
     * @param response the HttpClientResponse instance
     */
    public static void handleResponse(Object request, Object response) {
        Span span = IN_FLIGHT.remove(System.identityHashCode(request));
        if (span == null) return;

        try {
            int statusCode = extractStatusCode(response);
            if (statusCode > 0) {
                span.setAttribute(SemanticAttributes.HTTP_STATUS_CODE, (long) statusCode);
                if (statusCode >= 400) {
                    span.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
                }
            }
        } finally {
            span.end();
        }
    }

    /**
     * Called when an exception occurs on the HTTP client request (connection
     * refused, timeout, etc.). Records the exception and ends the span.
     *
     * @param request the HttpClientRequestImpl instance (for map lookup)
     * @param thrown  the exception
     */
    public static void handleException(Object request, Throwable thrown) {
        Span span = IN_FLIGHT.remove(System.identityHashCode(request));
        if (span == null) return;

        try {
            if (thrown != null) {
                span.recordException(thrown,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, thrown.getMessage());
            }
        } finally {
            span.end();
        }
    }

    private static int extractStatusCode(Object response) {
        try {
            Object code = response.getClass().getMethod("statusCode").invoke(response);
            return (code instanceof Integer) ? (Integer) code : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String extractMethod(Object request) {
        try {
            // HttpClientRequestBase.method() returns HttpMethod
            Object method = request.getClass().getMethod("method").invoke(request);
            return method != null ? method.toString() : "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private static String extractHost(Object request) {
        try {
            // Try getHost() first (Vert.x 3.9+)
            return (String) request.getClass().getMethod("getHost").invoke(request);
        } catch (Exception e1) {
            try {
                return (String) request.getClass().getMethod("host").invoke(request);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String extractUri(Object request) {
        try {
            return (String) request.getClass().getMethod("uri").invoke(request);
        } catch (Exception e) {
            try {
                return (String) request.getClass().getMethod("getURI").invoke(request);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static int extractPort(Object request) {
        try {
            Object port = request.getClass().getMethod("getPort").invoke(request);
            if (port instanceof Integer) return (Integer) port;
            return (int) request.getClass().getMethod("port").invoke(request);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String buildUrl(String host, int port, String uri) {
        if (host == null) return uri;
        String scheme = (port == 443) ? "https" : "http";
        String portSuffix = (port == 80 || port == 443 || port <= 0) ? "" : ":" + port;
        return scheme + "://" + host + portSuffix + uri;
    }
}
