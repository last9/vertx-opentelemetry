package io.last9.tracing.otel.v3.agent;

import io.last9.tracing.otel.v3.TracedRouter;
import io.vertx.reactivex.ext.web.Router;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code Router.router(Vertx)}.
 *
 * <p>After the original method returns a plain Router, this advice installs
 * OpenTelemetry tracing handlers on it — the same handlers that
 * {@link TracedRouter#create} would install. The idempotency guard in
 * {@link TracedRouter#instrumentExisting} prevents double-instrumentation
 * if the application also uses {@code TracedRouter.create()} explicitly.
 *
 * <p>The {@code suppress = Throwable.class} ensures that any failure in
 * the advice does not prevent the Router from being created.
 */
public class RouterAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(@Advice.Return Router router) {
        TracedRouter.instrumentExisting(router);
    }
}
