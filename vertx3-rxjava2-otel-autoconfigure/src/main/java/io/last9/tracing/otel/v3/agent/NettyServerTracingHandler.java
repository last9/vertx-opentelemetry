package io.last9.tracing.otel.v3.agent;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
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
 * Netty pipeline handler that creates SERVER spans for HTTP requests.
 *
 * <p>Injected by {@link NettyServerPipelineAdvice} when {@code HttpServerCodec} is
 * detected in the Netty pipeline. Same approach as Datadog and OTel Java agents.
 *
 * <h2>Scope management</h2>
 * <p>This handler does NOT hold an OTel scope open across the request lifecycle.
 * The Vert.x event loop is single-threaded — holding a scope open from channelRead
 * to write would leak into subsequent requests on the same thread.
 *
 * <p>Instead, the span is stored in a Netty channel attribute. {@link HttpServerAdviceHelper}
 * checks the {@link #NETTY_SERVER_SPAN} ThreadLocal and adopts the span (makes it current
 * within the handler scope). If HttpServerAdviceHelper doesn't fire, this handler still
 * creates and ends the span with correct attributes.
 *
 * <h2>Deduplication</h2>
 * <p>If both this handler and HttpServerAdviceHelper fire:
 * <ul>
 *   <li>This handler creates the span in channelRead</li>
 *   <li>HttpServerAdviceHelper sees the span via ThreadLocal and skips creation</li>
 *   <li>HttpServerAdviceHelper manages the scope within handler.handle()</li>
 *   <li>This handler ends the span when the response is written</li>
 * </ul>
 */
public final class NettyServerTracingHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(NettyServerTracingHandler.class);
    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";
    private static final String HANDLER_NAME = "last9-http-server-tracing";

    /**
     * ThreadLocal holding the Netty-created span for the current request.
     * Set in channelRead, checked by {@link HttpServerAdviceHelper} in the same
     * call stack (event loop thread is single-threaded). Cleared when the response
     * is written or the connection closes.
     */
    public static final ThreadLocal<Span> NETTY_SERVER_SPAN = new ThreadLocal<>();

    /** Stores the span for the current request on this channel (survives across events). */
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
     * Called from {@link NettyServerPipelineAdvice}. Injects this handler into the
     * pipeline when {@code HttpServerCodec} is detected.
     */
    public static void maybeInject(Object handler, Object pipeline) {
        try {
            if (!(handler instanceof HttpServerCodec)) {
                return;
            }
            if (!(pipeline instanceof ChannelPipeline)) {
                return;
            }
            ChannelPipeline p = (ChannelPipeline) pipeline;

            // Don't inject twice on the same pipeline
            if (p.get(HANDLER_NAME) != null) {
                return;
            }

            // Insert after the codec so we see decoded HttpRequest/HttpResponse objects
            String codecName = p.context((io.netty.channel.ChannelHandler) handler).name();
            p.addAfter(codecName, HANDLER_NAME, new NettyServerTracingHandler());
            log.info("NettyServerTracingHandler: injected into Netty pipeline after {}", codecName);
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to inject into pipeline: {}", t.getMessage());
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;
            System.err.println("[Last9 OTel Agent] NettyServerTracingHandler.channelRead: "
                    + req.method() + " " + req.uri());
            startServerSpan(ctx, req);
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        if (msg instanceof HttpResponse) {
            HttpResponse resp = (HttpResponse) msg;
            System.err.println("[Last9 OTel Agent] NettyServerTracingHandler.write: status="
                    + resp.status().code());
            setResponseStatus(ctx, resp);
        }
        if (msg instanceof LastHttpContent) {
            System.err.println("[Last9 OTel Agent] NettyServerTracingHandler.write: LastHttpContent — ending span");
            endServerSpan(ctx);
        }
        super.write(ctx, msg, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        endServerSpan(ctx);
        super.channelInactive(ctx);
    }

    private void startServerSpan(ChannelHandlerContext ctx, HttpRequest request) {
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

            // Parse Host header for server.address / server.port
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

            // Store span in channel attribute (persists across Netty events)
            ctx.channel().attr(SERVER_SPAN_KEY).set(span);

            // Store span in ThreadLocal for HttpServerAdviceHelper to adopt.
            // The call stack is: channelRead → Vert.x handler dispatch → handle(request)
            // — all on the same event loop thread, synchronous.
            NETTY_SERVER_SPAN.set(span);
        } catch (Throwable t) {
            log.warn("NettyServerTracingHandler: failed to start span: {}", t.getMessage());
        }
    }

    private void setResponseStatus(ChannelHandlerContext ctx, HttpResponse response) {
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

    private void endServerSpan(ChannelHandlerContext ctx) {
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
