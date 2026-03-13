package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for HTTP client exception handling.
 *
 * <p>Intercepts {@code io.vertx.core.http.impl.HttpClientRequestBase.handleException(Throwable)}
 * which is called when an exception occurs on the HTTP client request (connection
 * refused, timeout, DNS failure, etc.). Retrieves the in-flight span created by
 * {@link NettyHttpClientAdvice}, records the exception, and ends the span.
 */
public class NettyHttpExceptionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.This Object request,
            @Advice.Argument(0) Throwable thrown) {

        NettyHttpClientHelper.handleException(request, thrown);
    }
}
