package io.last9.tracing.otel.v4.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code io.vertx.tracing.opentelemetry.OpenTelemetryTracer.receiveResponse()}.
 *
 * <p>Fired on entry — before the SPI ends the CLIENT span — to add {@code http.status_code}
 * and set {@code StatusCode.ERROR} for 4xx/5xx responses or exceptions. The SPI
 * {@code vertx-opentelemetry} never sets these attributes, leaving CLIENT spans without
 * error status even for failed requests.
 *
 * <p>Uses {@code @OnMethodEnter} so the span is still alive when we mutate it;
 * the SPI ends the span inside {@code receiveResponse()} after our advice returns.
 */
public class HttpClientReceiveAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(1) Object response,   // R response — HttpClientResponse after erasure
            @Advice.Argument(2) Object operation,  // Operation wrapping the SPI span
            @Advice.Argument(3) Throwable failure) {
        HttpTracerHelper.enrichOnReceive(response, operation, failure);
    }
}
