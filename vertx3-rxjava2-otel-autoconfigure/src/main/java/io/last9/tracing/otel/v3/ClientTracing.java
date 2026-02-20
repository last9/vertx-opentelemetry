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
 * <h2>Usage</h2>
 * <pre>{@code
 * import io.last9.tracing.otel.v3.ClientTracing;
 *
 * // Wrap the request before calling rxSend():
 * ClientTracing.inject(webClient.getAbs(pricingServiceUrl + "/v1/price/" + symbol))
 *     .rxSend()
 *     .subscribe(...);
 * }</pre>
 *
 * <p>Call {@code inject} inside a handler that runs while a span is active (i.e., inside a
 * {@link TracedRouter} route handler). If called outside an active span, the propagator
 * injects a no-op {@code traceparent} and has no effect.
 *
 * @see TracedRouter
 */
public final class ClientTracing {

    private ClientTracing() {
        // Utility class
    }

    /**
     * Injects the current OpenTelemetry trace context into the given HTTP request as a W3C
     * {@code traceparent} header. Uses {@link GlobalOpenTelemetry#get()} to obtain the
     * propagator — suitable for production use after {@link OtelLauncher} has initialized the SDK.
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
     * supplied {@link OpenTelemetry} instance. Useful when the SDK is not registered globally
     * (e.g., in tests that construct their own {@code OpenTelemetrySdk}).
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
