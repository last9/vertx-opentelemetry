package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for HTTP client response handling.
 *
 * <p>Intercepts {@code io.vertx.core.http.impl.HttpClientRequestBase.handleResponse(HttpClientResponse)}
 * which is called when the HTTP response is received from the server. Retrieves the
 * in-flight span created by {@link NettyHttpClientAdvice}, sets the HTTP status code,
 * and ends the span — giving the span the full round-trip duration.
 */
public class NettyHttpResponseAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.This Object request,
            @Advice.Argument(0) Object response) {

        NettyHttpClientHelper.handleResponse(request, response);
    }
}
