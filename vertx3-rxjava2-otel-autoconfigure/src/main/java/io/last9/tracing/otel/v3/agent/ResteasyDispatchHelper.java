package io.last9.tracing.otel.v3.agent;

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

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Helper methods called by {@link ResteasyDispatchAdvice} to create SERVER spans
 * for JAX-RS requests dispatched by RESTEasy on Vert.x.
 *
 * <p>All RESTEasy types ({@code HttpRequest}, {@code HttpResponse}, {@code UriInfo},
 * {@code HttpHeaders}) are accessed via reflection so that this library has no
 * compile-time dependency on RESTEasy. This works across RESTEasy 3.x and 4.x.
 *
 * <p>The OTel context (with the SERVER span) is made current via a ThreadLocal
 * {@link Scope}. This is safe because {@code SynchronousDispatcher.invoke()} is
 * synchronous — the enter and exit run on the same thread with no interleaving.
 */
public final class ResteasyDispatchHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    /**
     * Holds the OTel {@link Scope} between enter and exit of the dispatch method.
     * Safe because {@code SynchronousDispatcher.invoke()} is synchronous.
     */
    private static final ThreadLocal<Scope> SCOPE_HOLDER = new ThreadLocal<>();

    /**
     * Extracts W3C trace context headers from a RESTEasy HttpRequest via reflection.
     * The carrier is the HttpRequest object; headers are accessed via
     * {@code getHttpHeaders().getHeaderString(name)} and
     * {@code getHttpHeaders().getRequestHeaders()}.
     */
    private static final TextMapGetter<Object> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Object carrier) {
            try {
                Object headers = carrier.getClass().getMethod("getHttpHeaders").invoke(carrier);
                @SuppressWarnings("unchecked")
                Map<String, List<String>> map = (Map<String, List<String>>)
                        headers.getClass().getMethod("getRequestHeaders").invoke(headers);
                return map != null ? map.keySet() : Collections.emptyList();
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        @Override
        public String get(Object carrier, String key) {
            try {
                Object headers = carrier.getClass().getMethod("getHttpHeaders").invoke(carrier);
                return (String) headers.getClass()
                        .getMethod("getHeaderString", String.class)
                        .invoke(headers, key);
            } catch (Exception e) {
                return null;
            }
        }
    };

    private ResteasyDispatchHelper() {}

    /**
     * Creates a SERVER span from the RESTEasy HttpRequest and makes the OTel
     * context current. The scope is stored in a ThreadLocal for cleanup in
     * {@link #endSpan}.
     *
     * @param requestObj the RESTEasy HttpRequest (accessed via reflection)
     * @return the span, or null if creation failed
     */
    public static Span startSpan(Object requestObj) {
        try {
            String method = (String) requestObj.getClass()
                    .getMethod("getHttpMethod").invoke(requestObj);
            Object uriInfo = requestObj.getClass()
                    .getMethod("getUri").invoke(requestObj);
            String path = (String) uriInfo.getClass()
                    .getMethod("getPath").invoke(uriInfo);

            if (method == null) method = "UNKNOWN";
            if (path == null) path = "/";

            OpenTelemetry otel = GlobalOpenTelemetry.get();
            Tracer tracer = otel.getTracer(TRACER_NAME);
            TextMapPropagator propagator = otel.getPropagators().getTextMapPropagator();

            Context parentContext = propagator.extract(Context.root(), requestObj, HEADER_GETTER);

            Span span = tracer.spanBuilder(method + " " + path)
                    .setParent(parentContext)
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute(SemanticAttributes.HTTP_REQUEST_METHOD, method)
                    .setAttribute(SemanticAttributes.URL_PATH, path)
                    .startSpan();

            // Extract additional HTTP semantic attributes from the request URI
            try {
                Object requestUri = uriInfo.getClass().getMethod("getRequestUri").invoke(uriInfo);
                if (requestUri instanceof URI) {
                    URI uri = (URI) requestUri;
                    if (uri.getScheme() != null) {
                        span.setAttribute(SemanticAttributes.URL_SCHEME, uri.getScheme());
                    }
                    if (uri.getHost() != null) {
                        span.setAttribute(SemanticAttributes.SERVER_ADDRESS, uri.getHost());
                    }
                    if (uri.getPort() > 0) {
                        span.setAttribute(SemanticAttributes.SERVER_PORT, (long) uri.getPort());
                    }
                    if (uri.getQuery() != null) {
                        span.setAttribute(SemanticAttributes.URL_QUERY, uri.getQuery());
                    }
                }
            } catch (Exception ignored) {
                // getRequestUri() may not be available in all RESTEasy versions
            }

            // User-Agent header
            try {
                Object headers = requestObj.getClass().getMethod("getHttpHeaders").invoke(requestObj);
                String userAgent = (String) headers.getClass()
                        .getMethod("getHeaderString", String.class).invoke(headers, "User-Agent");
                if (userAgent != null) {
                    span.setAttribute(SemanticAttributes.USER_AGENT_ORIGINAL, userAgent);
                }
            } catch (Exception ignored) {}

            Context otelContext = parentContext.with(span);
            Scope scope = otelContext.makeCurrent();
            SCOPE_HOLDER.set(scope);

            return span;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ends the SERVER span, recording the response status and any exception.
     * Closes the OTel scope stored by {@link #startSpan}.
     *
     * @param span        the span from startSpan (nullable)
     * @param responseObj the RESTEasy HttpResponse (accessed via reflection, nullable)
     * @param thrown      exception thrown during dispatch (nullable)
     */
    public static void endSpan(Span span, Object responseObj, Throwable thrown) {
        if (span == null) return;

        try {
            if (thrown != null) {
                span.recordException(thrown,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, thrown.getMessage());
            }

            if (responseObj != null) {
                try {
                    Object statusObj = responseObj.getClass()
                            .getMethod("getStatus").invoke(responseObj);
                    int status = (int) statusObj;
                    span.setAttribute(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE, (long) status);
                    if (status >= 500) {
                        span.setStatus(StatusCode.ERROR);
                    }
                } catch (Exception ignored) {
                    // Response may not support getStatus() in some RESTEasy versions
                }
            }
        } finally {
            Scope scope = SCOPE_HOLDER.get();
            if (scope != null) {
                scope.close();
                SCOPE_HOLDER.remove();
            }
            span.end();
        }
    }
}
