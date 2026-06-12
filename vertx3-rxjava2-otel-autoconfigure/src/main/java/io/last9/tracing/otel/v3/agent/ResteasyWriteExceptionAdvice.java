package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code SynchronousDispatcher.writeException(HttpRequest, HttpResponse,
 * Throwable[, Consumer])}.
 *
 * <p>RESTEasy calls this for both sync internal errors and async CompletionStage exceptions.
 * For sync errors, {@link ResteasyDispatchAdvice}'s {@code @Advice.Thrown} is null because
 * RESTEasy catches internally; for async errors, the SERVER span is no longer current after
 * {@link ResteasyDispatchHelper#closeScope()} ran. In both cases, this advice records the
 * exception and ends the span via the span stored on the request attributes.
 */
public class ResteasyWriteExceptionAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response,
            @Advice.Argument(2) Throwable throwable) {
        ResteasyDispatchHelper.endSpanFromWriteException(request, response, throwable);
    }
}
