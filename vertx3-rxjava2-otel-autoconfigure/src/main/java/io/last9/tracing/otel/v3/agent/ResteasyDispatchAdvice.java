package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code org.jboss.resteasy.core.SynchronousDispatcher.invoke(HttpRequest, HttpResponse)}.
 *
 * <p>Creates a SERVER span for each JAX-RS request dispatched by RESTEasy on Vert.x.
 * The OTel context is made current during the dispatch so that downstream CLIENT
 * spans (DB, HTTP, Kafka) become children of the SERVER span.
 *
 * <p>Uses {@link ResteasyDispatchHelper} which accesses RESTEasy types via reflection,
 * avoiding a compile-time dependency on RESTEasy.
 */
public class ResteasyDispatchAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(0) Object request,
            @Advice.Local("otelSpan") Span span) {

        span = ResteasyDispatchHelper.startSpan(request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response,
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span) {

        ResteasyDispatchHelper.endSpan(span, request, response, thrown);
    }
}
