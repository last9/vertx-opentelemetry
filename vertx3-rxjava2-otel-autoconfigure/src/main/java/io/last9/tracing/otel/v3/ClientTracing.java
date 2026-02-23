package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.vertx.reactivex.ext.web.client.HttpRequest;

/**
 * Utility for propagating OpenTelemetry trace context into outgoing Vert.x 3 WebClient requests.
 *
 * <p>Vert.x 3 has no client-side tracing SPI, so outgoing HTTP requests do not automatically
 * carry the W3C {@code traceparent} header. Without it, downstream services start a new root
 * span rather than a child of the current trace, breaking the distributed trace chain and
 * causing spans to appear disconnected in your observability platform.
 *
 * <h2>Usage (recommended) — with CLIENT span</h2>
 * <pre>{@code
 * import io.last9.tracing.otel.v3.ClientTracing;
 *
 * // Creates a CLIENT span and injects traceparent from it:
 * ClientTracing.traced(webClient.getAbs(pricingServiceUrl + "/v1/price/" + symbol))
 *     .rxSend()
 *     .subscribe(...);
 * }</pre>
 *
 * <h2>Usage (lightweight) — inject only, no CLIENT span</h2>
 * <pre>{@code
 * // Only injects traceparent header, no CLIENT span created:
 * ClientTracing.inject(webClient.getAbs(url))
 *     .rxSend()
 *     .subscribe(...);
 * }</pre>
 *
 * <p>Call either method inside a handler that runs while a span is active (i.e., inside a
 * {@link TracedRouter} route handler). If called outside an active span, the W3C propagator
 * writes no headers at all.
 *
 * @see TracedWebClient
 * @see TracedRouter
 */
public final class ClientTracing {

    private ClientTracing() {
        // Utility class
    }

    /**
     * Wraps the given HTTP request with a {@link TracedHttpRequest} that creates an
     * OpenTelemetry CLIENT span when {@code rxSend()} is called. The {@code traceparent}
     * header is injected from the CLIENT span's context, ensuring correct parent-child
     * relationships in downstream services.
     *
     * <p>This is the recommended method — it follows OTel HTTP client semantic conventions.
     *
     * @param <T>     the response body type
     * @param request the outgoing WebClient request
     * @return a traced request that creates a CLIENT span on send
     */
    public static <T> HttpRequest<T> traced(HttpRequest<T> request) {
        return traced(request, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps the given HTTP request with a {@link TracedHttpRequest} using the supplied
     * {@link OpenTelemetry} instance. Useful in tests.
     *
     * @param <T>            the response body type
     * @param request        the outgoing WebClient request
     * @param openTelemetry  the OpenTelemetry instance to use
     * @return a traced request that creates a CLIENT span on send
     */
    public static <T> HttpRequest<T> traced(HttpRequest<T> request, OpenTelemetry openTelemetry) {
        // Extract method and URI from the underlying HttpRequestImpl.
        // The core HttpRequest interface only has setters; getters are on the impl class.
        io.vertx.ext.web.client.impl.HttpRequestImpl<?> impl =
                (io.vertx.ext.web.client.impl.HttpRequestImpl<?>) request.getDelegate();
        String method = impl.method().name();
        String host = impl.host();
        int port = impl.port();
        String uri = impl.uri();
        String url = (impl.ssl() != null && impl.ssl() ? "https" : "http")
                + "://" + host + (port > 0 ? ":" + port : "") + uri;
        return new TracedHttpRequest<>(request, openTelemetry, method, url);
    }

    /**
     * Injects the current OpenTelemetry trace context into the given HTTP request as a W3C
     * {@code traceparent} header. Uses {@link GlobalOpenTelemetry#get()} to obtain the
     * propagator.
     *
     * <p><strong>Note:</strong> This method only injects the header — it does NOT create a
     * CLIENT span. For full OTel semantic convention compliance, use {@link #traced(HttpRequest)}
     * instead.
     *
     * @param <T>     the response body type
     * @param request the outgoing WebClient request
     * @return the same request with the {@code traceparent} (and any other propagation) headers set
     */
    public static <T> HttpRequest<T> inject(HttpRequest<T> request) {
        return inject(request, GlobalOpenTelemetry.get());
    }

    /**
     * Injects the current OpenTelemetry trace context into the given HTTP request using the
     * supplied {@link OpenTelemetry} instance.
     *
     * <p><strong>Note:</strong> This method only injects the header — it does NOT create a
     * CLIENT span. For full OTel semantic convention compliance, use
     * {@link #traced(HttpRequest, OpenTelemetry)} instead.
     *
     * @param <T>            the response body type
     * @param request        the outgoing WebClient request
     * @param openTelemetry  the OpenTelemetry instance whose propagators should be used
     * @return the same request with the {@code traceparent} (and any other propagation) headers set
     */
    public static <T> HttpRequest<T> inject(HttpRequest<T> request, OpenTelemetry openTelemetry) {
        openTelemetry
                .getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), request,
                        (req, key, value) -> req.putHeader(key, value));
        return request;
    }
}
