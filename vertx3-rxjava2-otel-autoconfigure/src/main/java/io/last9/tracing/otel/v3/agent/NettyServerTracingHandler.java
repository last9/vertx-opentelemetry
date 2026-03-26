package io.last9.tracing.otel.v3.agent;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
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

/**
 * Netty pipeline tracing for HTTP servers — architecture matches the OTel Java agent.
 *
 * <h2>Handler architecture</h2>
 * <ul>
 *   <li>{@link RequestHandler} (inbound) — after {@code HttpRequestDecoder}/{@code HttpServerCodec}.
 *       Creates SERVER span, makes it current via OTel scope for downstream handlers.</li>
 *   <li>{@link ResponseHandler} (outbound) — after {@code HttpResponseEncoder}/{@code HttpServerCodec}.
 *       Sets response attributes, ends span on {@code LastHttpContent} write.</li>
 * </ul>
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
     */
    static final class RequestHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpRequest) {
                Context otelContext = startServerSpan(ctx, (HttpRequest) msg);
                if (otelContext != null) {
                    // Make span current for downstream handlers (Router, user code)
                    try (Scope ignored = otelContext.makeCurrent()) {
                        super.channelRead(ctx, msg);
                    } catch (Throwable t) {
                        endServerSpan(ctx, t);
                        throw t;
                    }
                    return;
                }
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
     */
    static final class ResponseHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
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
                // Store response for chunked case (headers come before body)
                ctx.channel().attr(SERVER_RESPONSE_KEY).set(response);
            }

            if (msg instanceof LastHttpContent) {
                // End span. Use new promise if void to avoid silent listener failure.
                if (otelContext != null) {
                    Span span = Span.fromContext(otelContext);
                    if (span.isRecording()) {
                        if (promise.isVoid()) {
                            // VoidChannelPromise ignores listeners — end immediately
                            endServerSpan(ctx, null);
                        } else {
                            // End span after write completes (captures write failures)
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
            // Deduplication: if a SERVER span already exists, skip creation.
            // This prevents duplicates when both Netty and Vert.x-level tracing fire.
            Span existing = Span.current();
            if (existing.getSpanContext().isValid()
                    && existing.getSpanContext().isRemote() == false) {
                // A local span is already active — don't create another
                return null;
            }

            Tracer tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME);
            TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();

            String method = request.method().name();
            String uri = request.uri();
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;

            // Extract parent context from traceparent headers
            Context parentContext = propagator.extract(Context.root(), request, HEADER_GETTER);

            Span span = tracer.spanBuilder(method + " " + path)
                    .setParent(parentContext)
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.request.method", method)
                    .setAttribute("url.path", path)
                    .startSpan();

            // Parse Host header
            String host = request.headers().get("Host");
            if (host != null) {
                String serverAddr = host;
                if (host.contains(":")) {
                    int idx = host.lastIndexOf(':');
                    serverAddr = host.substring(0, idx);
                    try {
                        long port = Long.parseLong(host.substring(idx + 1));
                        span.setAttribute(
                                io.opentelemetry.api.common.AttributeKey.longKey("server.port"), port);
                    } catch (NumberFormatException ignored) {}
                }
                span.setAttribute("server.address", serverAddr);
            }

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
            span.setAttribute(
                    io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code"),
                    (long) statusCode);
            if (statusCode >= 500) {
                span.setStatus(StatusCode.ERROR);
            }
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to set response status: {}", t.getMessage());
        }
    }

    private static void endServerSpan(ChannelHandlerContext ctx, Throwable error) {
        Context otelContext = ctx.channel().attr(SERVER_CONTEXT_KEY).getAndSet(null);
        ctx.channel().attr(SERVER_RESPONSE_KEY).set(null);
        if (otelContext == null) return;
        try {
            Span span = Span.fromContext(otelContext);
            if (error != null) {
                span.recordException(error);
                span.setStatus(StatusCode.ERROR, error.getMessage());
            }
            span.end();
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to end span: {}", t.getMessage());
        }
    }
}
