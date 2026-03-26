package io.last9.tracing.otel.v4.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice that intercepts {@code io.vertx.rxjava3.ext.web.Router.router(Vertx)}
 * to install {@link io.last9.tracing.otel.v4.SpanNameUpdater} on the returned Router.
 *
 * <p>The Vert.x 4 {@code VertxTracer} SPI creates HTTP spans with just the method name
 * (e.g., "GET"). This advice adds route-pattern naming so spans show "GET /v1/users/:id".
 */
public class RxRouterAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return Object router) {
        RxRouterAdviceHelper.instrumentIfNeeded(router);
    }
}
