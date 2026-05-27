package io.last9.tracing.otel.v4.agent;

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

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response) {
        // Re-run setup in case invoke()'s attempt got a null/stale OutputStream (RESTEasy 4.x
        // may create the real OutputStream only when the async response is about to be written).
        // captureResponseSetup is idempotent — skips if capture buffer already set.
        ResteasyDispatchHelper.captureResponseSetup(request, response);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response,
            @Advice.Thrown Throwable thrown) {
        ResteasyDispatchHelper.endSpanFromAsync(request, response, thrown);
    }
}
