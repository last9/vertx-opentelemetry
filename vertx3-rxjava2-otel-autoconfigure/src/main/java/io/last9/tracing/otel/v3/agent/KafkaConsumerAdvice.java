package io.last9.tracing.otel.v3.agent;

import io.vertx.core.Handler;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for {@code io.vertx.kafka.client.consumer.impl.KafkaReadStreamImpl.handler(Handler)}.
 *
 * <p>Intercepts the per-record handler registration and wraps it with a CONSUMER span
 * that extracts the topic, partition, offset, and traceparent from each record.
 *
 * <p>Uses raw Handler type because KafkaReadStreamImpl.handler() takes
 * Handler&lt;ConsumerRecord&lt;K,V&gt;&gt; (raw Kafka type).
 */
public class KafkaConsumerAdvice {

    @SuppressWarnings("unchecked")
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(value = 0, readOnly = false) Handler handler) {

        handler = KafkaConsumerHelper.wrapRawHandler(handler);
    }
}
