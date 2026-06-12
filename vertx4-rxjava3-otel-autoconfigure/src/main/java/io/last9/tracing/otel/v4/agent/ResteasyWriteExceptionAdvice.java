package io.last9.tracing.otel.v4.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code SynchronousDispatcher.writeException(HttpRequest, HttpResponse,
 * Throwable[, Consumer])}.
 *
 * <p>RESTEasy calls this for both sync internal errors and async CompletionStage exceptions.
 * On the servlet (Undertow) async path, {@code AsyncResponseConsumer.internalResume(Throwable)}
 * calls writeException directly; for an unhandled exception it rethrows as UnhandledException.
 * We store the throwable on the request at onEnter (so {@code endSpan} can record it) and end
 * the span at onExit, which fires on the exceptional exit thanks to {@code onThrowable}.
 */
public class ResteasyWriteExceptionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(0) Object request,
            @Advice.Argument(2) Throwable throwable) {
        ResteasyDispatchHelper.storeAsyncException(request, throwable);
    }

    // onThrowable is required: in the servlet (Undertow) async path, AsyncResponseConsumer
    // calls writeException directly and it rethrows as UnhandledException, so the span must be
    // ended on the exceptional exit, not only on a normal return.
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response,
            @Advice.Argument(2) Throwable throwable) {
        ResteasyDispatchHelper.endSpanFromWriteException(request, response, throwable);
    }
}
