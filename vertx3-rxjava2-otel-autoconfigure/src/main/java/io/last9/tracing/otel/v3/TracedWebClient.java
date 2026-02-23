package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.core.net.SocketAddress;
import io.vertx.reactivex.ext.web.client.HttpRequest;
import io.vertx.reactivex.ext.web.client.WebClient;

/**
 * A drop-in replacement for Vert.x 3 {@link WebClient} that automatically creates
 * OpenTelemetry CLIENT spans for every outgoing HTTP request and injects the W3C
 * {@code traceparent} header.
 *
 * <p>Vert.x 3 has no client-side tracing SPI, so outgoing HTTP requests produce no spans
 * and do not carry trace context automatically. {@code TracedWebClient} solves both:
 * <ul>
 *   <li>Creates a CLIENT span per OTel HTTP semantic conventions with attributes:
 *       {@code http.request.method}, {@code url.full}, {@code server.address},
 *       {@code server.port}, {@code http.response.status_code}</li>
 *   <li>Injects {@code traceparent} from the CLIENT span's context (not the parent),
 *       so downstream services see the CLIENT span as their parent</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Instead of: WebClient client = WebClient.create(vertx);
 * WebClient client = TracedWebClient.create(vertx);
 *
 * // CLIENT span + traceparent injection happen automatically:
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
     * @return a WebClient that creates CLIENT spans and auto-injects {@code traceparent}
     */
    public static TracedWebClient create(Vertx vertx) {
        return new TracedWebClient(WebClient.create(vertx), GlobalOpenTelemetry.get());
    }

    /**
     * Creates a new {@code TracedWebClient} with options using {@link GlobalOpenTelemetry}.
     *
     * @param vertx   the Vert.x instance
     * @param options the WebClient options
     * @return a WebClient that creates CLIENT spans and auto-injects {@code traceparent}
     */
    public static TracedWebClient create(Vertx vertx, WebClientOptions options) {
        return new TracedWebClient(WebClient.create(vertx, options), GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an existing {@link WebClient} with tracing using {@link GlobalOpenTelemetry}.
     *
     * @param client the existing WebClient to wrap
     * @return a WebClient that creates CLIENT spans and auto-injects {@code traceparent}
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
     * @return a WebClient that creates CLIENT spans and auto-injects {@code traceparent}
     */
    public static TracedWebClient wrap(WebClient client, OpenTelemetry openTelemetry) {
        return new TracedWebClient(client, openTelemetry);
    }

    private <T> HttpRequest<T> traced(HttpRequest<T> request, String method, String url) {
        return new TracedHttpRequest<>(request, openTelemetry, method, url);
    }

    private <T> HttpRequest<T> traced(HttpRequest<T> request, String method,
                                       int port, String host, String requestURI) {
        return new TracedHttpRequest<>(request, openTelemetry, method, port, host, requestURI);
    }

    private <T> HttpRequest<T> traced(HttpRequest<T> request, String method,
                                       String host, String requestURI) {
        return new TracedHttpRequest<>(request, openTelemetry, method, host, requestURI);
    }

    // ---- GET ----

    @Override
    public HttpRequest<Buffer> get(String requestURI) {
        return traced(delegate.get(requestURI), "GET", requestURI);
    }

    @Override
    public HttpRequest<Buffer> get(int port, String host, String requestURI) {
        return traced(delegate.get(port, host, requestURI), "GET", port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> get(String host, String requestURI) {
        return traced(delegate.get(host, requestURI), "GET", host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> getAbs(String absoluteURI) {
        return traced(delegate.getAbs(absoluteURI), "GET", absoluteURI);
    }

    // ---- POST ----

    @Override
    public HttpRequest<Buffer> post(String requestURI) {
        return traced(delegate.post(requestURI), "POST", requestURI);
    }

    @Override
    public HttpRequest<Buffer> post(int port, String host, String requestURI) {
        return traced(delegate.post(port, host, requestURI), "POST", port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> post(String host, String requestURI) {
        return traced(delegate.post(host, requestURI), "POST", host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> postAbs(String absoluteURI) {
        return traced(delegate.postAbs(absoluteURI), "POST", absoluteURI);
    }

    // ---- PUT ----

    @Override
    public HttpRequest<Buffer> put(String requestURI) {
        return traced(delegate.put(requestURI), "PUT", requestURI);
    }

    @Override
    public HttpRequest<Buffer> put(int port, String host, String requestURI) {
        return traced(delegate.put(port, host, requestURI), "PUT", port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> put(String host, String requestURI) {
        return traced(delegate.put(host, requestURI), "PUT", host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> putAbs(String absoluteURI) {
        return traced(delegate.putAbs(absoluteURI), "PUT", absoluteURI);
    }

    // ---- DELETE ----

    @Override
    public HttpRequest<Buffer> delete(String requestURI) {
        return traced(delegate.delete(requestURI), "DELETE", requestURI);
    }

    @Override
    public HttpRequest<Buffer> delete(int port, String host, String requestURI) {
        return traced(delegate.delete(port, host, requestURI), "DELETE", port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> delete(String host, String requestURI) {
        return traced(delegate.delete(host, requestURI), "DELETE", host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> deleteAbs(String absoluteURI) {
        return traced(delegate.deleteAbs(absoluteURI), "DELETE", absoluteURI);
    }

    // ---- PATCH ----

    @Override
    public HttpRequest<Buffer> patch(String requestURI) {
        return traced(delegate.patch(requestURI), "PATCH", requestURI);
    }

    @Override
    public HttpRequest<Buffer> patch(int port, String host, String requestURI) {
        return traced(delegate.patch(port, host, requestURI), "PATCH", port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> patch(String host, String requestURI) {
        return traced(delegate.patch(host, requestURI), "PATCH", host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> patchAbs(String absoluteURI) {
        return traced(delegate.patchAbs(absoluteURI), "PATCH", absoluteURI);
    }

    // ---- HEAD ----

    @Override
    public HttpRequest<Buffer> head(String requestURI) {
        return traced(delegate.head(requestURI), "HEAD", requestURI);
    }

    @Override
    public HttpRequest<Buffer> head(int port, String host, String requestURI) {
        return traced(delegate.head(port, host, requestURI), "HEAD", port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> head(String host, String requestURI) {
        return traced(delegate.head(host, requestURI), "HEAD", host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> headAbs(String absoluteURI) {
        return traced(delegate.headAbs(absoluteURI), "HEAD", absoluteURI);
    }

    // ---- Generic request / requestAbs ----

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, String requestURI) {
        return traced(delegate.request(method, requestURI), method.name(), requestURI);
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, int port, String host, String requestURI) {
        return traced(delegate.request(method, port, host, requestURI),
                method.name(), port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, String host, String requestURI) {
        return traced(delegate.request(method, host, requestURI),
                method.name(), host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, RequestOptions options) {
        return traced(delegate.request(method, options), method.name(),
                options.getHost() + ":" + options.getPort() + options.getURI());
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress,
                                        int port, String host, String requestURI) {
        return traced(delegate.request(method, serverAddress, port, host, requestURI),
                method.name(), port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress,
                                        String host, String requestURI) {
        return traced(delegate.request(method, serverAddress, host, requestURI),
                method.name(), host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress,
                                        String requestURI) {
        return traced(delegate.request(method, serverAddress, requestURI),
                method.name(), requestURI);
    }

    @Override
    public HttpRequest<Buffer> request(HttpMethod method, SocketAddress serverAddress,
                                        RequestOptions options) {
        return traced(delegate.request(method, serverAddress, options), method.name(),
                options.getHost() + ":" + options.getPort() + options.getURI());
    }

    @Override
    public HttpRequest<Buffer> requestAbs(HttpMethod method, String absoluteURI) {
        return traced(delegate.requestAbs(method, absoluteURI), method.name(), absoluteURI);
    }

    @Override
    public HttpRequest<Buffer> requestAbs(HttpMethod method, SocketAddress serverAddress,
                                           String absoluteURI) {
        return traced(delegate.requestAbs(method, serverAddress, absoluteURI),
                method.name(), absoluteURI);
    }

    // ---- RAW (custom HTTP method as String) ----

    @Override
    public HttpRequest<Buffer> raw(String method, String requestURI) {
        return traced(delegate.raw(method, requestURI), method, requestURI);
    }

    @Override
    public HttpRequest<Buffer> raw(String method, int port, String host, String requestURI) {
        return traced(delegate.raw(method, port, host, requestURI),
                method, port, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> raw(String method, String host, String requestURI) {
        return traced(delegate.raw(method, host, requestURI), method, host, requestURI);
    }

    @Override
    public HttpRequest<Buffer> rawAbs(String method, String absoluteURI) {
        return traced(delegate.rawAbs(method, absoluteURI), method, absoluteURI);
    }

    // ---- Lifecycle ----

    @Override
    public void close() {
        delegate.close();
    }
}
