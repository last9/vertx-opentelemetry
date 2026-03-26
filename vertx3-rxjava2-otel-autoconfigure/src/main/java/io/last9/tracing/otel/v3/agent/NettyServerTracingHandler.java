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
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty pipeline tracing for HTTP servers — same architecture as the OTel Java agent.
 *
 * <p>Two separate handlers are injected into the pipeline:
 * <ul>
 *   <li>{@link RequestHandler} (inbound) — after {@code HttpRequestDecoder} or {@code HttpServerCodec}.
 *       Creates SERVER span on {@code channelRead(HttpRequest)}.</li>
 *   <li>{@link ResponseHandler} (outbound) — after {@code HttpResponseEncoder} or {@code HttpServerCodec}.
 *       Ends SERVER span on {@code write(LastHttpContent)}.</li>
 * </ul>
 *
 * <p>Vert.x 3.9.x uses separate {@code VertxHttpRequestDecoder} + {@code VertxHttpResponseEncoder}
 * instead of the combined {@code HttpServerCodec}. Both patterns are detected.
 *
 * <p>The outbound handler MUST be positioned after the encoder so it sees decoded
 * {@code HttpResponse}/{@code LastHttpContent} objects, not raw {@code ByteBuf}.
 * Vert.x writes via {@code ChannelHandlerContext.write()} from the VertxHandler position;
 * the write flows backward through the pipeline and hits our handler before the encoder.
 */
public final class NettyServerTracingHandler {

    private static final Logger log = LoggerFactory.getLogger(NettyServerTracingHandler.class);
    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";
    private static final String REQUEST_HANDLER_NAME = "last9-http-server-request";
    private static final String RESPONSE_HANDLER_NAME = "last9-http-server-response";

    public static final ThreadLocal<Span> NETTY_SERVER_SPAN = new ThreadLocal<>();

    private static final AttributeKey<Span> SERVER_SPAN_KEY =
            AttributeKey.valueOf("last9.http.server.span");

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

    /**
     * Called from pipeline advices. Detects HTTP codec handlers and injects
     * the appropriate tracing handler.
     */
    public static void maybeInject(Object handler, Object pipeline) {
        try {
            if (!(pipeline instanceof ChannelPipeline)) return;
            ChannelPipeline p = (ChannelPipeline) pipeline;
            String handlerName = p.context((io.netty.channel.ChannelHandler) handler).name();

            if (handler instanceof HttpServerCodec) {
                // Combined codec — inject both request and response handlers after it
                injectRequestHandler(p, handlerName);
                injectResponseHandler(p, handlerName);
            } else if (handler instanceof HttpRequestDecoder) {
                // Separate decoder (Vert.x 3.9.x VertxHttpRequestDecoder)
                injectRequestHandler(p, handlerName);
            } else if (handler instanceof HttpResponseEncoder) {
                // Separate encoder (Vert.x 3.9.x VertxHttpResponseEncoder)
                injectResponseHandler(p, handlerName);
            }
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to inject: {}", t.getMessage());
        }
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

    /**
     * Inbound handler — creates SERVER span when HTTP request arrives.
     */
    static final class RequestHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpRequest) {
                startServerSpan(ctx, (HttpRequest) msg);
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            endServerSpan(ctx);
            super.channelInactive(ctx);
        }
    }

    /**
     * Outbound handler — ends SERVER span when HTTP response is written.
     * Positioned after the encoder so it sees HttpResponse/LastHttpContent,
     * not raw ByteBuf.
     */
    static final class ResponseHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            System.err.println("[Last9 OTel Agent] ResponseHandler.write: " + msg.getClass().getName());
            if (msg instanceof HttpResponse) {
                setResponseStatus(ctx, (HttpResponse) msg);
            }
            if (msg instanceof LastHttpContent) {
                // End span immediately — VoidChannelPromise doesn't support listeners
                endServerSpan(ctx);
            }
            super.write(ctx, msg, promise);
        }
    }

    // --- Shared span lifecycle methods ---

    private static void startServerSpan(ChannelHandlerContext ctx, HttpRequest request) {
        try {
            Tracer tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME);
            TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();

            String method = request.method().name();
            String uri = request.uri();
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;

            Context parentContext = propagator.extract(Context.root(), request, HEADER_GETTER);

            Span span = tracer.spanBuilder(method + " " + path)
                    .setParent(parentContext)
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.request.method", method)
                    .setAttribute("url.path", path)
                    .startSpan();

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

            ctx.channel().attr(SERVER_SPAN_KEY).set(span);
            NETTY_SERVER_SPAN.set(span);
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to start span: {}", t.getMessage());
        }
    }

    private static void setResponseStatus(ChannelHandlerContext ctx, HttpResponse response) {
        Span span = ctx.channel().attr(SERVER_SPAN_KEY).get();
        if (span == null) return;
        try {
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

    private static void endServerSpan(ChannelHandlerContext ctx) {
        Span span = ctx.channel().attr(SERVER_SPAN_KEY).getAndSet(null);
        if (span == null) return;
        try {
            span.end();
            NETTY_SERVER_SPAN.remove();
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to end span: {}", t.getMessage());
        }
    }
}
