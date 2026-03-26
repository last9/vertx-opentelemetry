package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code DefaultChannelPipeline.addBefore(String, String, ChannelHandler)}.
 *
 * <p>Vert.x 3 uses {@code addBefore("handler", "codec", new HttpServerCodec(...))} to set up
 * the HTTP pipeline — NOT {@code addLast}. This advice covers that code path.
 */
public class NettyServerPipelineBeforeAdvice {

    @Advice.OnMethodExit
    static void onExit(@Advice.Argument(2) Object handler, @Advice.This Object pipeline) {
        try {
            NettyServerTracingHandler.maybeInject(handler, pipeline);
        } catch (Throwable t) {
            System.err.println("[Last9 OTel Agent] NettyServerPipelineBeforeAdvice.onExit FAILED: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
