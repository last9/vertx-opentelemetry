package io.last9.tracing.otel.v3.agent;

import io.vertx.core.Handler;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code io.vertx.kafka.client.consumer.impl.KafkaReadStreamImpl.handler(Handler)}.
 *
 * <p>Intercepts the per-record handler registration and wraps it with a CONSUMER span
 * that extracts the topic, partition, offset, and traceparent from each record.
 *
 * <p>This enables zero-code consumer tracing — every record dispatched to the user's
 * handler automatically gets a CONSUMER span linked to the producer span.
 */
public class KafkaConsumerAdvice {

    @SuppressWarnings("unchecked")
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(value = 0, readOnly = false) Handler<KafkaConsumerRecord> handler) {

        handler = KafkaConsumerHelper.wrapHandler(handler);
    }
}
