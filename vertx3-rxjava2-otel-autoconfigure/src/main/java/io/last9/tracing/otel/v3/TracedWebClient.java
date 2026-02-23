package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.core.net.SocketAddress;
import io.vertx.reactivex.ext.web.client.HttpRequest;
import io.vertx.reactivex.ext.web.client.WebClient;

/**
 * A drop-in replacement for Vert.x 3 {@link WebClient} that automatically injects the
 * W3C {@code traceparent} header into every outgoing request.
 *
 * <p>Vert.x 3 has no client-side tracing SPI, so outgoing HTTP requests do not carry trace
 * context automatically. Without propagation, downstream services start new root spans and
 * distributed traces appear disconnected.
 *
 * <p>{@code TracedWebClient} solves this by injecting the current span's trace context into
 * every request created through this client. Customers no longer need to call
 * {@link ClientTracing#inject(HttpRequest)} on each request individually.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Instead of: WebClient client = WebClient.create(vertx);
 * WebClient client = TracedWebClient.create(vertx);
 *
 * // traceparent is injected automatically — no ClientTracing.inject() needed:
 * client.getAbs("http://pricing-service/v1/price/AAPL")
 *     .rxSend()
 *     .subscribe(...);
 * }</pre>
 *
 * <h2>Wrapping an existing WebClient</h2>
 * <pre>{@code
 * WebClient existing = WebClient.create(vertx, options);
 * WebClient traced = TracedWebClient.wrap(existing);
 * }</pre>
 *
 * <p><strong>Note:</strong> The trace context is captured when the request is created (e.g.,
 * when {@code get()}, {@code post()}, etc. are called), not when {@code rxSend()} is called.
 * This works correctly for the typical Vert.x pattern where requests are created and sent in
 * the same handler chain.
 *
 * @see ClientTracing
 * @see TracedRouter
 */
public final class TracedWebClient extends WebClient {

    private final WebClient delegate;
    private final OpenTelemetry openTelemetry;

    private TracedWebClient(WebClient delegate, OpenTelemetry openTelemetry) {
        super(delegate.getDelegate());
        this.delegate = delegate;
        this.openTelemetry = openTelemetry;
    }

    /**
     * Creates a new {@code TracedWebClient} using {@link GlobalOpenTelemetry}.
     *
     * @param vertx the Vert.x instance
     * @return a WebClient that auto-injects {@code traceparent}
     */
    public static TracedWebClient create(Vertx vertx) {
        return new TracedWebClient(WebClient.create(vertx), GlobalOpenTelemetry.get());
    }

    /**
     * Creates a new {@code TracedWebClient} with options using {@link GlobalOpenTelemetry}.
     *
     * @param vertx   the Vert.x instance
     * @param options the WebClient options
     * @return a WebClient that auto-injects {@code traceparent}
     */
    public static TracedWebClient create(Vertx vertx, WebClientOptions options) {
        return new TracedWebClient(WebClient.create(vertx, options), GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an existing {@link WebClient} with tracing using {@link GlobalOpenTelemetry}.
     *
     * @param client the existing WebClient to wrap
     * @return a WebClient that auto-injects {@code traceparent}
     */
    public static TracedWebClient wrap(WebClient client) {
        return new TracedWebClient(client, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an existing {@link WebClient} with tracing using the supplied {@link OpenTelemetry}.
     * Useful in tests.
     *
     * @param client        the existing WebClient to wrap
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a WebClient that auto-injects {@code traceparent}
     */
    public static TracedWebClient wrap(WebClient client, OpenTelemetry openTelemetry) {
        return new TracedWebClient(client, openTelemetry);
    }

    private <T> HttpRequest<T> inject(HttpRequest<T> request) {
        openTelemetry
                .getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), request,
                        (req, key, value) -> req.putHeader(key, value));
        return request;
    }

    // ---- GET ----

    @Override
    public HttpRequest<Buffer> get(String requestURI) {
        return inject(delegate.get(requestURI));
    }

    @Override
    public HttpRequest<Buffer> get(int port, String host, String requestURI) {
        return inject(delegate.get(port, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> get(String host, String requestURI) {
        return inject(delegate.get(host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> getAbs(String absoluteURI) {
        return inject(delegate.getAbs(absoluteURI));
    }

    // ---- POST ----

    @Override
    public HttpRequest<Buffer> post(String requestURI) {
        return inject(delegate.post(requestURI));
    }

    @Override
    public HttpRequest<Buffer> post(int port, String host, String requestURI) {
        return inject(delegate.post(port, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> post(String host, String requestURI) {
        return inject(delegate.post(host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> postAbs(String absoluteURI) {
        return inject(delegate.postAbs(absoluteURI));
    }

    // ---- PUT ----

    @Override
    public HttpRequest<Buffer> put(String requestURI) {
        return inject(delegate.put(requestURI));
    }

    @Override
    public HttpRequest<Buffer> put(int port, String host, String requestURI) {
        return inject(delegate.put(port, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> put(String host, String requestURI) {
        return inject(delegate.put(host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> putAbs(String absoluteURI) {
        return inject(delegate.putAbs(absoluteURI));
    }

    // ---- DELETE ----

    @Override
    public HttpRequest<Buffer> delete(String requestURI) {
        return inject(delegate.delete(requestURI));
    }

    @Override
    public HttpRequest<Buffer> delete(int port, String host, String requestURI) {
        return inject(delegate.delete(port, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> delete(String host, String requestURI) {
        return inject(delegate.delete(host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> deleteAbs(String absoluteURI) {
        return inject(delegate.deleteAbs(absoluteURI));
    }

    // ---- PATCH ----

    @Override
    public HttpRequest<Buffer> patch(String requestURI) {
        return inject(delegate.patch(requestURI));
    }

    @Override
    public HttpRequest<Buffer> patch(int port, String host, String requestURI) {
        return inject(delegate.patch(port, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> patch(String host, String requestURI) {
        return inject(delegate.patch(host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> patchAbs(String absoluteURI) {
        return inject(delegate.patchAbs(absoluteURI));
    }

    // ---- HEAD ----

    @Override
    public HttpRequest<Buffer> head(String requestURI) {
        return inject(delegate.head(requestURI));
    }

    @Override
    public HttpRequest<Buffer> head(int port, String host, String requestURI) {
        return inject(delegate.head(port, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> head(String host, String requestURI) {
        return inject(delegate.head(host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> headAbs(String absoluteURI) {
        return inject(delegate.headAbs(absoluteURI));
    }

    // ---- Generic request / requestAbs ----

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, String requestURI) {
        return inject(delegate.request(method, requestURI));
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, int port, String host, String requestURI) {
        return inject(delegate.request(method, port, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, String host, String requestURI) {
        return inject(delegate.request(method, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, RequestOptions options) {
        return inject(delegate.request(method, options));
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress, int port, String host, String requestURI) {
        return inject(delegate.request(method, serverAddress, port, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress, String host, String requestURI) {
        return inject(delegate.request(method, serverAddress, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress, String requestURI) {
        return inject(delegate.request(method, serverAddress, requestURI));
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress, RequestOptions options) {
        return inject(delegate.request(method, serverAddress, options));
    }

    @Override
    public HttpRequest<Buffer> requestAbs(HttpMethod method, String absoluteURI) {
        return inject(delegate.requestAbs(method, absoluteURI));
    }

    @Override
    public HttpRequest<Buffer> requestAbs(HttpMethod method, SocketAddress serverAddress, String absoluteURI) {
        return inject(delegate.requestAbs(method, serverAddress, absoluteURI));
    }

    // ---- RAW (custom HTTP method as String) ----

    @Override
    public HttpRequest<Buffer> raw(String method, String requestURI) {
        return inject(delegate.raw(method, requestURI));
    }

    @Override
    public HttpRequest<Buffer> raw(String method, int port, String host, String requestURI) {
        return inject(delegate.raw(method, port, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> raw(String method, String host, String requestURI) {
        return inject(delegate.raw(method, host, requestURI));
    }

    @Override
    public HttpRequest<Buffer> rawAbs(String method, String absoluteURI) {
        return inject(delegate.rawAbs(method, absoluteURI));
    }

    // ---- Lifecycle ----

    @Override
    public void close() {
        delegate.close();
    }
}
