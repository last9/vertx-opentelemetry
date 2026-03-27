package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for Vert.x HTTP client requests — applied to all implementations
 * of {@code io.vertx.core.http.HttpClientRequest} (same approach as OTel Java agent).
 *
 * <p>Applied to all {@code end*()} overloads and {@code sendHead()} because in Vert.x 3
 * each overload is an independent code path: {@code end(Buffer chunk)} does NOT call
 * the no-arg {@code end()}, so every variant must be intercepted to cover GET (no body)
 * and POST/PUT (with body) requests.
 *
 * <p>Creates a CLIENT span, injects the {@code traceparent} header for distributed
 * tracing, and stores the span for later completion by {@link NettyHttpResponseAdvice}
 * or {@link NettyHttpExceptionAdvice}. The span is NOT ended here — it is ended when
 * the response arrives or an exception occurs, covering the full round-trip time.
 *
 * <p>The {@code IN_HTTP_CLIENT_CALL} ThreadLocal guard in {@link NettyHttpClientHelper}
 * prevents duplicate CLIENT spans when {@link io.last9.tracing.otel.v3.TracedWebClient}
 * is also active for the same request.
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
