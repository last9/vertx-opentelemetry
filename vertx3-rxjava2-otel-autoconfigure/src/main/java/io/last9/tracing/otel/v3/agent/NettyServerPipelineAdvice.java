package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice that intercepts {@code DefaultChannelPipeline.addLast()} to
 * inject HTTP server tracing handlers into the Netty pipeline.
 *
 * <p>This is the same approach used by both the Datadog and OTel Java agents —
 * intercept at the Netty pipeline level to guarantee SERVER spans for every
 * HTTP request, regardless of how the Vert.x application sets up its handlers.
 *
 * <p>When {@code HttpServerCodec} is added to the pipeline, this advice injects
 * {@link NettyServerTracingHandler} immediately after it.
 */
public class NettyServerPipelineAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(@Advice.Argument(1) Object handler, @Advice.This Object pipeline) {
        NettyServerTracingHandler.maybeInject(handler, pipeline);
    }
}
