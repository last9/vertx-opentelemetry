package io.last9.tracing.otel.v4.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code org.jboss.resteasy.core.AsyncResponseConsumer.complete(Throwable)}.
 *
 * <p>In the RESTEasy-on-servlet (Undertow) async model, a CompletionStage handler's result is
 * delivered via {@code AsyncResponseConsumer}, NOT through {@code SynchronousDispatcher
 * .asynchronousDelivery}. {@code complete(Throwable)} is {@code final} on the base class and is
 * the single point invoked at the end of both the success (throwable == null) and failure paths,
 * after the response body has been written through the TeeOutputStream. This is the reliable
 * place to end the SERVER span for async requests.
 */
public class ResteasyAsyncCompleteAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.This Object consumer,
            @Advice.Argument(0) Throwable throwable) {
        ResteasyDispatchHelper.endSpanFromAsyncConsumer(consumer, throwable);
    }
}
