package io.last9.tracing.otel.v3.agent;

import io.last9.tracing.otel.v3.TracedWebClient;
import io.vertx.reactivex.ext.web.client.WebClient;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code WebClient.create(Vertx)} and
 * {@code WebClient.create(Vertx, WebClientOptions)}.
 *
 * <p>After the original factory method returns a plain WebClient, this advice
 * wraps it with {@link TracedWebClient} so every outgoing HTTP request
 * automatically gets a CLIENT span and {@code traceparent} header injection.
 *
 * <p>The {@code instanceof} check prevents double-wrapping if the application
 * already uses {@code TracedWebClient.create()} explicitly.
 */
public class WebClientAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(@Advice.Return(readOnly = false) WebClient client) {
        if (!(client instanceof TracedWebClient)) {
            client = TracedWebClient.wrap(client);
        }
    }
}
