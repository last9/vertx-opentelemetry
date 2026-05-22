package io.last9.tracing.otel.v3.agent;

import io.last9.tracing.otel.v3.BodyCaptureConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;

/**
 * Netty pipeline tracing for HTTP servers — architecture matches the OTel Java agent.
 *
 * <h2>Handler architecture</h2>
 * <ul>
 *   <li>{@link RequestHandler} (inbound) — after {@code HttpRequestDecoder}/{@code HttpServerCodec}.
 *       Creates SERVER span, makes it current via OTel scope for downstream handlers.
 *       Accumulates request body bytes when body capture is enabled.</li>
 *   <li>{@link ResponseHandler} (outbound) — after {@code HttpResponseEncoder}/{@code HttpServerCodec}.
 *       Sets response attributes, ends span on {@code LastHttpContent} write.
 *       Accumulates response body bytes when body capture is enabled.</li>
 * </ul>
 *
 * <h2>Body capture</h2>
 * <p>Controlled by {@link BodyCaptureConfig}. Bytes are read non-destructively from
 * {@code ByteBuf} via {@code getBytes()} (not {@code readBytes()}), so downstream handlers
 * see the full content unchanged. Accumulated in per-channel {@link ByteArrayOutputStream}
 * attributes, set as span attributes in {@code endServerSpan()}.
 *
 * <h2>Deduplication</h2>
 * <p>Before creating a span, checks if a SERVER span already exists in the current
 * context (via {@code Span.current()}). This prevents duplicate spans when both
 * Netty and Vert.x-level (HttpServerAdvice/TracedRouter) instrumentation are active.
 *
 * <h2>Context propagation</h2>
 * <p>Stores the full OTel {@link Context} (not just Span) in a Netty channel attribute.
 * Makes the context current during {@code channelRead} so downstream handlers
 * (Router, user code) see the span via {@code Span.current()}.
 *
 * <h2>Codec detection</h2>
 * <p>Detects {@code HttpServerCodec} (combined), {@code HttpRequestDecoder} (Vert.x 3.9.x
 * uses {@code VertxHttpRequestDecoder}), and {@code HttpResponseEncoder} (Vert.x 3.9.x
 * uses {@code VertxHttpResponseEncoder}).
 */
public final class NettyServerTracingHandler {

    private static final Logger log = LoggerFactory.getLogger(NettyServerTracingHandler.class);
    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";
    private static final String REQUEST_HANDLER_NAME = "last9-http-server-request";
    private static final String RESPONSE_HANDLER_NAME = "last9-http-server-response";

    /** Full OTel context for the in-flight request (includes span + baggage). */
    private static final AttributeKey<Context> SERVER_CONTEXT_KEY =
            AttributeKey.valueOf("last9.http.server.context");

    /** HTTP response stored when headers arrive (for chunked responses). */
    private static final AttributeKey<HttpResponse> SERVER_RESPONSE_KEY =
            AttributeKey.valueOf("last9.http.server.response");

    /** Whether to accumulate request body bytes (path + content-type filter result). */
    private static final AttributeKey<Boolean> BODY_REQUEST_ACTIVE_KEY =
            AttributeKey.valueOf("last9.body.request.active");

    /** Accumulated request body bytes. Non-null only when active. */
    private static final AttributeKey<ByteArrayOutputStream> BODY_REQUEST_BYTES_KEY =
            AttributeKey.valueOf("last9.body.request.bytes");

    /** Accumulated response body bytes. Non-null only when content-type filter matched. */
    private static final AttributeKey<ByteArrayOutputStream> BODY_RESPONSE_BYTES_KEY =
            AttributeKey.valueOf("last9.body.response.bytes");

    // Pre-allocated attribute keys — avoid per-request allocation
    private static final io.opentelemetry.api.common.AttributeKey<Long> ATTR_SERVER_PORT =
            io.opentelemetry.api.common.AttributeKey.longKey("server.port");
    private static final io.opentelemetry.api.common.AttributeKey<Long> ATTR_STATUS_CODE =
            io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code");
    private static final io.opentelemetry.api.common.AttributeKey<String> ATTR_REQ_BODY =
            io.opentelemetry.api.common.AttributeKey.stringKey("http.request.body");
    private static final io.opentelemetry.api.common.AttributeKey<String> ATTR_RESP_BODY =
            io.opentelemetry.api.common.AttributeKey.stringKey("http.response.body");

    // Cached tracer and propagator — initialized lazily on first request
    private static volatile Tracer cachedTracer;
    private static volatile TextMapPropagator cachedPropagator;

    private static final TextMapGetter<HttpRequest> HEADER_GETTER = new TextMapGetter<HttpRequest>() {
        @Override
        public Iterable<String> keys(HttpRequest carrier) {
            return carrier.headers().names();
        }

        @Override
        public String get(HttpRequest carrier, String key) {
            return carrier.headers().get(key);
        }
    };

    private NettyServerTracingHandler() {}

    // ---- Pipeline injection ----

    /**
     * Called from pipeline advices (addLast, addBefore, addFirst, addAfter, replace).
     * Detects HTTP codec handlers and injects the appropriate tracing handler.
     */
    public static void maybeInject(Object handler, Object pipeline) {
        try {
            if (!(pipeline instanceof ChannelPipeline)) return;
            ChannelPipeline p = (ChannelPipeline) pipeline;

            io.netty.channel.ChannelHandlerContext handlerCtx =
                    p.context((io.netty.channel.ChannelHandler) handler);
            if (handlerCtx == null) return; // handler not in pipeline (removed?)
            String handlerName = handlerCtx.name();

            if (handler instanceof HttpServerCodec) {
                injectRequestHandler(p, handlerName);
                injectResponseHandler(p, handlerName);
            } else if (handler instanceof HttpRequestDecoder) {
                injectRequestHandler(p, handlerName);
            } else if (handler instanceof HttpResponseEncoder) {
                injectResponseHandler(p, handlerName);
            }
        } catch (IllegalArgumentException e) {
            // Handler with same name already exists — safe to ignore
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to inject: {}", t.getMessage());
        }
    }

    /**
     * Called when a codec handler is removed from the pipeline.
     * Removes associated tracing handlers to prevent orphans.
     */
    public static void maybeRemove(Object handler, Object pipeline) {
        try {
            if (!(pipeline instanceof ChannelPipeline)) return;
            ChannelPipeline p = (ChannelPipeline) pipeline;

            if (handler instanceof HttpServerCodec || handler instanceof HttpRequestDecoder) {
                if (p.get(REQUEST_HANDLER_NAME) != null) {
                    p.remove(REQUEST_HANDLER_NAME);
                }
            }
            if (handler instanceof HttpServerCodec || handler instanceof HttpResponseEncoder) {
                if (p.get(RESPONSE_HANDLER_NAME) != null) {
                    p.remove(RESPONSE_HANDLER_NAME);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Package-private: reset cached state between tests. */
    static void resetForTest() {
        cachedTracer = null;
        cachedPropagator = null;
    }

    private static void injectRequestHandler(ChannelPipeline p, String afterName) {
        if (p.get(REQUEST_HANDLER_NAME) != null) return;
        p.addAfter(afterName, REQUEST_HANDLER_NAME, new RequestHandler());
        log.info("NettyServerTracingHandler: request handler injected after {}", afterName);
    }

    private static void injectResponseHandler(ChannelPipeline p, String afterName) {
        if (p.get(RESPONSE_HANDLER_NAME) != null) return;
        p.addAfter(afterName, RESPONSE_HANDLER_NAME, new ResponseHandler());
        log.info("NettyServerTracingHandler: response handler injected after {}", afterName);
    }

    // ---- Inbound handler (request) ----

    /**
     * Creates SERVER span on HTTP request arrival. Makes the OTel context current
     * during {@code channelRead} so downstream handlers see the span.
     * Also accumulates request body bytes when body capture is enabled.
     */
    static final class RequestHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpRequest) {
                HttpRequest request = (HttpRequest) msg;
                Context otelContext = startServerSpan(ctx, request);

                // Decide whether to accumulate request body
                if (BodyCaptureConfig.enabled() && BodyCaptureConfig.captureRequest()) {
                    String ct = request.headers().get("Content-Type");
                    String uri = request.uri();
                    int q = uri.indexOf('?');
                    String path = q >= 0 ? uri.substring(0, q) : uri;
                    boolean active = BodyCaptureConfig.isAllowedContentType(ct)
                            && BodyCaptureConfig.isAllowedPath(path);
                    ctx.channel().attr(BODY_REQUEST_ACTIVE_KEY).set(active);
                }

                // For FullHttpRequest (also implements HttpContent), body bytes arrive here
                if (msg instanceof HttpContent) {
                    accumulateRequestBody(ctx, (HttpContent) msg);
                }

                if (otelContext != null) {
                    try (Scope ignored = otelContext.makeCurrent()) {
                        super.channelRead(ctx, msg);
                    } catch (Throwable t) {
                        endServerSpan(ctx, t);
                        throw t;
                    }
                    return;
                }
            }

            // Chunked request: body arrives as separate HttpContent frames after HttpRequest
            if (msg instanceof HttpContent) {
                accumulateRequestBody(ctx, (HttpContent) msg);
            }

            super.channelRead(ctx, msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            endServerSpan(ctx, null);
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            // Record exception on span but don't end it — response write will end it
            Context otelContext = ctx.channel().attr(SERVER_CONTEXT_KEY).get();
            if (otelContext != null) {
                Span span = Span.fromContext(otelContext);
                if (span.isRecording()) {
                    span.recordException(cause);
                    span.setStatus(StatusCode.ERROR, cause.getMessage());
                }
            }
            super.exceptionCaught(ctx, cause);
        }
    }

    // ---- Outbound handler (response) ----

    /**
     * Ends SERVER span when HTTP response is written. Positioned after the encoder
     * so it sees {@code HttpResponse}/{@code LastHttpContent}, not raw {@code ByteBuf}.
     * Also accumulates response body bytes when body capture is enabled.
     */
    static final class ResponseHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            // Pass through raw ByteBuf frames — we only handle HTTP-level objects
            if (!(msg instanceof HttpResponse) && !(msg instanceof HttpContent)) {
                super.write(ctx, msg, promise);
                return;
            }

            Context otelContext = ctx.channel().attr(SERVER_CONTEXT_KEY).get();

            if (msg instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) msg;
                // Skip WebSocket upgrade (101) — connection continues, don't end span here
                if (response.status().equals(HttpResponseStatus.SWITCHING_PROTOCOLS)) {
                    ctx.channel().attr(SERVER_CONTEXT_KEY).set(null);
                    super.write(ctx, msg, promise);
                    return;
                }
                setResponseStatus(otelContext, response);
                ctx.channel().attr(SERVER_RESPONSE_KEY).set(response);

                // Allocate response body accumulator if content-type is allowed
                if (BodyCaptureConfig.enabled() && BodyCaptureConfig.captureResponse()) {
                    String ct = response.headers().get("Content-Type");
                    if (BodyCaptureConfig.isAllowedContentType(ct)) {
                        ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).set(new ByteArrayOutputStream());
                    }
                }
            }

            // Accumulate response body bytes (covers both chunked and full responses)
            if (msg instanceof HttpContent) {
                ByteArrayOutputStream baos = ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).get();
                if (baos != null) {
                    ByteBuf content = ((HttpContent) msg).content();
                    int readable = content.readableBytes();
                    int capacity = BodyCaptureConfig.maxBytes() - baos.size();
                    if (capacity > 0) {
                        int toRead = Math.min(readable, capacity);
                        byte[] tmp = new byte[toRead];
                        content.getBytes(content.readerIndex(), tmp); // non-destructive
                        baos.write(tmp, 0, toRead);
                    }
                }
            }

            if (msg instanceof LastHttpContent) {
                if (otelContext != null) {
                    Span span = Span.fromContext(otelContext);
                    if (span.isRecording()) {
                        if (promise.isVoid()) {
                            endServerSpan(ctx, null);
                        } else {
                            promise.addListener(future -> {
                                if (!future.isSuccess()) {
                                    span.setStatus(StatusCode.ERROR, "Write failed");
                                    if (future.cause() != null) {
                                        span.recordException(future.cause());
                                    }
                                }
                                endServerSpan(ctx, null);
                            });
                        }
                    }
                }
            }

            // Make context current during write so downstream sees the span
            if (otelContext != null) {
                try (Scope ignored = otelContext.makeCurrent()) {
                    super.write(ctx, msg, promise);
                }
            } else {
                super.write(ctx, msg, promise);
            }
        }
    }

    // ---- Shared span lifecycle ----

    /**
     * Creates a SERVER span if none exists in the current context (dedup check).
     * Returns the OTel context with the span, or null if suppressed.
     */
    private static Context startServerSpan(ChannelHandlerContext ctx, HttpRequest request) {
        try {
            // Deduplication: check channel attribute, not Span.current() (thread-scoped).
            // Channel attribute is per-connection, immune to stale scopes on event loop threads.
            if (ctx.channel().attr(SERVER_CONTEXT_KEY).get() != null) {
                return null;
            }

            // Lazy-init tracer and propagator (cached after first request)
            if (cachedTracer == null) {
                cachedTracer = GlobalOpenTelemetry.getTracer(TRACER_NAME);
                cachedPropagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();
            }

            String method = request.method().name();
            String uri = request.uri();
            int q = uri.indexOf('?');
            String path = q >= 0 ? uri.substring(0, q) : uri;

            Context parentContext = cachedPropagator.extract(Context.root(), request, HEADER_GETTER);

            Span span = cachedTracer.spanBuilder(method + " " + path)
                    .setParent(parentContext)
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.request.method", method)
                    .setAttribute("url.path", path)
                    .startSpan();

            applyHostHeader(span, request.headers().get("Host"));

            // Store full Context (not just Span) in channel attribute
            Context otelContext = parentContext.with(span);
            ctx.channel().attr(SERVER_CONTEXT_KEY).set(otelContext);

            return otelContext;
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to start span: {}", t.getMessage());
            return null;
        }
    }

    private static void setResponseStatus(Context otelContext, HttpResponse response) {
        if (otelContext == null) return;
        try {
            Span span = Span.fromContext(otelContext);
            int statusCode = response.status().code();
            span.setAttribute(ATTR_STATUS_CODE, (long) statusCode);
            if (statusCode >= 500) {
                span.setStatus(StatusCode.ERROR);
            }
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to set response status: {}", t.getMessage());
        }
    }

    /**
     * Shared host-header parser — extracts server.address and server.port from Host header.
     * Used by both Netty handler and HttpServerAdviceHelper.
     */
    static void applyHostHeader(Span span, String host) {
        if (host == null) return;
        String serverAddr = host;
        int idx = host.lastIndexOf(':');
        if (idx > 0) {
            serverAddr = host.substring(0, idx);
            try {
                span.setAttribute(ATTR_SERVER_PORT, Long.parseLong(host.substring(idx + 1)));
            } catch (NumberFormatException ignored) {}
        }
        span.setAttribute("server.address", serverAddr);
    }

    private static void accumulateRequestBody(ChannelHandlerContext ctx, HttpContent content) {
        Boolean active = ctx.channel().attr(BODY_REQUEST_ACTIVE_KEY).get();
        if (!Boolean.TRUE.equals(active)) return;
        ByteArrayOutputStream baos = ctx.channel().attr(BODY_REQUEST_BYTES_KEY).get();
        if (baos == null) {
            baos = new ByteArrayOutputStream();
            ctx.channel().attr(BODY_REQUEST_BYTES_KEY).set(baos);
        }
        ByteBuf buf = content.content();
        int readable = buf.readableBytes();
        int capacity = BodyCaptureConfig.maxBytes() - baos.size();
        if (capacity > 0) {
            int toRead = Math.min(readable, capacity);
            byte[] tmp = new byte[toRead];
            buf.getBytes(buf.readerIndex(), tmp); // non-destructive read
            baos.write(tmp, 0, toRead);
        }
    }

    private static void endServerSpan(ChannelHandlerContext ctx, Throwable error) {
        Context otelContext = ctx.channel().attr(SERVER_CONTEXT_KEY).getAndSet(null);
        HttpResponse httpResponse = ctx.channel().attr(SERVER_RESPONSE_KEY).getAndSet(null);
        ByteArrayOutputStream reqBytes = ctx.channel().attr(BODY_REQUEST_BYTES_KEY).getAndSet(null);
        ByteArrayOutputStream resBytes = ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).getAndSet(null);
        ctx.channel().attr(BODY_REQUEST_ACTIVE_KEY).set(null);

        if (otelContext == null) return;
        try {
            Span span = Span.fromContext(otelContext);
            if (error != null) {
                span.recordException(error);
                span.setStatus(StatusCode.ERROR, error.getMessage());
            }

            // Attach body attributes if enabled and conditions met
            if (BodyCaptureConfig.enabled() && span.isRecording()) {
                int statusCode = httpResponse != null ? httpResponse.status().code() : 0;
                boolean shouldAttach = !BodyCaptureConfig.errorOnly() || statusCode >= 400;
                if (shouldAttach) {
                    attachBodyAttr(span, ATTR_REQ_BODY, reqBytes);
                    attachBodyAttr(span, ATTR_RESP_BODY, resBytes);
                }
            }

            span.end();
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to end span: {}", t.getMessage());
        }
    }

    private static void attachBodyAttr(Span span,
            io.opentelemetry.api.common.AttributeKey<String> key,
            ByteArrayOutputStream baos) {
        if (baos == null || baos.size() == 0) return;
        try {
            String body = baos.toString("UTF-8");
            if (baos.size() >= BodyCaptureConfig.maxBytes()) {
                body += "[TRUNCATED]";
            }
            span.setAttribute(key, body);
        } catch (java.io.UnsupportedEncodingException ignored) {}
    }
}
