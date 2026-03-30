package io.last9.tracing.otel.v4.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code io.vertx.tracing.opentelemetry.OpenTelemetryTracer.sendRequest()}.
 *
 * <p>Fired on exit — after the SPI has already created the CLIENT span — to update the span
 * name from the bare HTTP method (e.g., {@code "GET"}) to {@code "{method} {path}"}
 * (e.g., {@code "GET /api/v1/users"}) and add peer/URL attributes.
 *
 * <p>Only HTTP client requests are enriched; Kafka producer and EventBus calls that also
 * route through {@code sendRequest()} are skipped via an interface-name check in
 * {@link HttpTracerHelper}.
 */
public class HttpClientSendAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(
            @Advice.Argument(3) Object request,   // R request — HttpClientRequest after erasure
            @Advice.Return Object operation) {     // Operation wrapping the SPI span
        HttpTracerHelper.enrichOnSend(request, operation);
    }
}
