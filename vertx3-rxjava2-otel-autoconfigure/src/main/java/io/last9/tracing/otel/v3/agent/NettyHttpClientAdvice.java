package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for Netty-based HTTP client requests at the Vert.x level.
 *
 * <p>Intercepts {@code io.vertx.core.http.impl.HttpClientRequestImpl.end()}
 * (no-arg variant) which is called when the HTTP request is written to the
 * network. Creates a CLIENT span, injects the {@code traceparent} header for
 * distributed tracing, and stores the span for later completion by
 * {@link NettyHttpResponseAdvice} or {@link NettyHttpExceptionAdvice}.
 *
 * <p>The span is NOT ended here — it is ended when the response arrives or
 * an exception occurs, so the span duration covers the full round-trip time.
 */
public class NettyHttpClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.This Object request,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        span = NettyHttpClientHelper.startSpan(request);
        if (span != null) {
            scope = span.makeCurrent();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.This Object request,
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span,
            @Advice.Local("otelScope") Scope scope) {

        NettyHttpClientHelper.exitSend(request, span, scope, thrown);
    }
}
