package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code org.jboss.resteasy.core.SynchronousDispatcher.writeException(
 * HttpRequest, HttpResponse, Throwable, Consumer)}.
 *
 * <p>RESTEasy calls this instead of throwing when handling async {@code CompletionStage}
 * responses that complete exceptionally. {@link ResteasyDispatchAdvice}'s {@code @Advice.Thrown}
 * is always null in that case — this advice bridges the gap by recording the exception on
 * the current OTel span while the RESTEasy SERVER span is still active.
 */
public class ResteasyWriteExceptionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(@Advice.Argument(2) Throwable throwable) {
        ResteasyDispatchHelper.recordAsyncException(throwable);
    }
}
