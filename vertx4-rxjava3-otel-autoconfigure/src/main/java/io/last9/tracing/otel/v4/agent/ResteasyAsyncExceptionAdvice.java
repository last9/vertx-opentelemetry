package io.last9.tracing.otel.v4.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code SynchronousDispatcher.asynchronousExceptionDelivery(
 * HttpRequest, HttpResponse, Throwable[, Consumer])}.
 *
 * <p>RESTEasy 4.x calls this when a CompletionStage completes exceptionally, rather than
 * going through {@code writeException} directly. This advice stores the throwable on the
 * request attributes at {@code onEnter} so that {@link ResteasyAsyncDeliveryAdvice} can
 * pick it up if {@code asynchronousDelivery} is called internally, and also ends the span
 * directly at {@code onExit} if it is still recording.
 */
public class ResteasyAsyncExceptionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(0) Object request,
            @Advice.Argument(2) Throwable throwable) {
        ResteasyDispatchHelper.storeAsyncException(request, throwable);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response,
            @Advice.Argument(2) Throwable throwable) {
        ResteasyDispatchHelper.endSpanFromWriteException(request, response, throwable);
    }
}
