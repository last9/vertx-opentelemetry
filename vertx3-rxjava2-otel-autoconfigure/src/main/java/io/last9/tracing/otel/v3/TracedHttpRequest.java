package io.last9.tracing.otel.v3;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.SemanticAttributes;
import io.reactivex.Single;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.MultiMap;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.core.streams.ReadStream;
import io.vertx.reactivex.ext.web.client.HttpRequest;
import io.vertx.reactivex.ext.web.client.HttpResponse;

import java.net.URI;
import java.util.function.Supplier;

/**
 * A wrapper around {@link HttpRequest} that creates an OpenTelemetry CLIENT span
 * when the request is sent.
 *
 * <p>Per the OTel HTTP client semantic conventions, every outbound HTTP call should
 * produce a CLIENT span with attributes: {@code http.request.method}, {@code url.full},
 * {@code server.address}, {@code server.port}, and {@code http.response.status_code}.
 *
 * <p>This class intercepts all {@code rxSend*()} methods to wrap them with a CLIENT span.
 * The traceparent header is injected from the CLIENT span's context (not the parent),
 * ensuring correct parent-child relationships in downstream services.
 *
 * <p>Not intended for direct construction — instances are created by {@link TracedWebClient}
 * and {@link ClientTracing}.
 *
 * @param <T> the response body type
 * @see TracedWebClient
 * @see ClientTracing
 */
class TracedHttpRequest<T> extends HttpRequest<T> {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    private final HttpRequest<T> delegate;
    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;
    private final String httpMethod;
    private final String url;
    private final String serverAddress;
    private final int serverPort;

    TracedHttpRequest(HttpRequest<T> delegate, OpenTelemetry openTelemetry,
                      String httpMethod, String url) {
        super(delegate.getDelegate());
        this.delegate = delegate;
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(TRACER_NAME);
        this.httpMethod = httpMethod;
        this.url = url;

        // Parse server address and port from URL
        String[] parsed = parseHostPort(url);
        this.serverAddress = parsed[0];
        this.serverPort = Integer.parseInt(parsed[1]);
    }

    TracedHttpRequest(HttpRequest<T> delegate, OpenTelemetry openTelemetry,
                      String httpMethod, int port, String host, String requestURI) {
        super(delegate.getDelegate());
        this.delegate = delegate;
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(TRACER_NAME);
        this.httpMethod = httpMethod;
        this.url = "http://" + host + ":" + port + requestURI;
        this.serverAddress = host;
        this.serverPort = port;
    }

    TracedHttpRequest(HttpRequest<T> delegate, OpenTelemetry openTelemetry,
                      String httpMethod, String host, String requestURI) {
        super(delegate.getDelegate());
        this.delegate = delegate;
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer(TRACER_NAME);
        this.httpMethod = httpMethod;
        this.serverAddress = host;
        this.serverPort = -1;
        this.url = "http://" + host + requestURI;
    }

    // ---- rxSend variants — each wrapped with a CLIENT span ----

    @Override
    public Single<HttpResponse<T>> rxSend() {
        return wrapWithSpan(() -> delegate.rxSend());
    }

    @Override
    public Single<HttpResponse<T>> rxSendBuffer(Buffer body) {
        return wrapWithSpan(() -> delegate.rxSendBuffer(body));
    }

    @Override
    public Single<HttpResponse<T>> rxSendJson(Object body) {
        return wrapWithSpan(() -> delegate.rxSendJson(body));
    }

    @Override
    public Single<HttpResponse<T>> rxSendJsonObject(JsonObject body) {
        return wrapWithSpan(() -> delegate.rxSendJsonObject(body));
    }

    @Override
    public Single<HttpResponse<T>> rxSendForm(MultiMap body) {
        return wrapWithSpan(() -> delegate.rxSendForm(body));
    }

    @Override
    public Single<HttpResponse<T>> rxSendStream(ReadStream<Buffer> body) {
        return wrapWithSpan(() -> delegate.rxSendStream(body));
    }

    // ---- Delegation for request-building methods ----
    // These must return `this` so that chained calls like
    // request.putHeader("X-Foo", "bar").rxSend() still hit our rxSend override.

    @Override
    public HttpRequest<T> putHeader(String name, String value) {
        delegate.putHeader(name, value);
        return this;
    }

    @Override
    public HttpRequest<T> timeout(long timeoutMs) {
        delegate.timeout(timeoutMs);
        return this;
    }

    @Override
    public HttpRequest<T> addQueryParam(String paramName, String paramValue) {
        delegate.addQueryParam(paramName, paramValue);
        return this;
    }

    @Override
    public HttpRequest<T> setQueryParam(String paramName, String paramValue) {
        delegate.setQueryParam(paramName, paramValue);
        return this;
    }

    @Override
    public HttpRequest<T> ssl(Boolean value) {
        delegate.ssl(value);
        return this;
    }

    @Override
    public HttpRequest<T> host(String value) {
        delegate.host(value);
        return this;
    }

    @Override
    public HttpRequest<T> port(int value) {
        delegate.port(value);
        return this;
    }

    @Override
    public HttpRequest<T> uri(String value) {
        delegate.uri(value);
        return this;
    }

    // ---- Span wrapping logic ----

    private Single<HttpResponse<T>> wrapWithSpan(Supplier<Single<HttpResponse<T>>> sendFn) {
        return Single.defer(() -> {
            // 1. Create CLIENT span per OTel HTTP client semantic conventions.
            //    Use the current context as parent so sibling CLIENT spans share
            //    the same parent (the SERVER span), not each other.
            Context parentContext = Context.current();
            Span span = tracer.spanBuilder(httpMethod)
                    .setParent(parentContext)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(SemanticAttributes.HTTP_REQUEST_METHOD, httpMethod)
                    .setAttribute(SemanticAttributes.URL_FULL, url)
                    .setAttribute(SemanticAttributes.SERVER_ADDRESS, serverAddress)
                    .startSpan();

            if (serverPort > 0) {
                span.setAttribute(SemanticAttributes.SERVER_PORT, (long) serverPort);
            }

            // 2. Build context containing the CLIENT span for header injection.
            //    IMPORTANT: Only make the context current briefly for injection,
            //    then close the scope immediately. Keeping the scope open across the
            //    async HTTP round-trip would pollute the event-loop thread-local,
            //    causing the next CLIENT span (from flatMap) to nest under this one
            //    instead of being a sibling.
            Context clientContext = parentContext.with(span);

            // 3. Inject traceparent from the CLIENT span's context (not the parent)
            try (Scope ignored = clientContext.makeCurrent()) {
                openTelemetry
                        .getPropagators()
                        .getTextMapPropagator()
                        .inject(clientContext, delegate,
                                (req, key, value) -> req.putHeader(key, value));
            }

            // 4. Execute the send and record response/error on the span.
            //    The span stays open (not ended) until the response arrives, but
            //    the scope is already closed so the thread-local context reverts to
            //    the parent — other concurrent outbound calls will correctly parent
            //    under the SERVER span.
            return sendFn.get()
                    .doOnSuccess(response -> {
                        int statusCode = response.statusCode();
                        span.setAttribute(SemanticAttributes.HTTP_RESPONSE_STATUS_CODE,
                                (long) statusCode);
                        if (statusCode >= 400) {
                            span.setStatus(StatusCode.ERROR);
                        }
                        span.end();
                    })
                    .doOnError(err -> {
                        span.recordException(err);
                        span.setStatus(StatusCode.ERROR, err.getMessage());
                        span.end();
                    })
                    .doOnDispose(() -> {
                        if (span.isRecording()) {
                            span.end();
                        }
                    });
        });
    }

    // ---- URL parsing ----

    private static String[] parseHostPort(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost() != null ? uri.getHost() : "unknown";
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equals(uri.getScheme()) ? 443 : 80;
            }
            return new String[]{host, String.valueOf(port)};
        } catch (Exception e) {
            return new String[]{"unknown", "-1"};
        }
    }
}
