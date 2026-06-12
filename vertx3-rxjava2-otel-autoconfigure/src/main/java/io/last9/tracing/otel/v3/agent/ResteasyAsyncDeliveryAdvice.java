package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code SynchronousDispatcher.asynchronousDelivery(
 * HttpRequest, HttpResponse, Response)}.
 *
 * <p>Called by RESTEasy when an async handler (CompletionStage, RxJava) completes successfully
 * and writes the response. The SERVER span created by {@link ResteasyDispatchAdvice} is still
 * alive at this point (invoke() closed the scope but did not end the span). This advice ends
 * the span after the response body has been written to the (TeeOutputStream-wrapped) stream.
 */
public class ResteasyAsyncDeliveryAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response,
            @Advice.Thrown Throwable thrown) {
        ResteasyDispatchHelper.endSpanFromAsync(request, response, thrown);
    }
}
