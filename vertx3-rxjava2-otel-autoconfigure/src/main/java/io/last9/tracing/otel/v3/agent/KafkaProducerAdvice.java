package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.trace.Span;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * ByteBuddy advice for {@code org.apache.kafka.clients.producer.KafkaProducer.send(ProducerRecord, Callback)}.
 *
 * <p>Intercepts at the raw kafka-clients level, covering ALL usage patterns
 * (direct, Vert.x wrapper, any framework). Creates a PRODUCER span and injects
 * {@code traceparent} into record headers for distributed trace propagation.
 *
 * <p>If the record already has a {@code traceparent} header (set by
 * {@code TracedKafkaProducer} or {@code KafkaTracing.tracedSend()}), the span
 * creation is skipped to prevent double-instrumentation.
 */
public class KafkaProducerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void onEnter(
            @Advice.Argument(0) ProducerRecord<?, ?> record,
            @Advice.Argument(value = 1, readOnly = false) Callback callback,
            @Advice.Local("otelSpan") Span span) {

        span = KafkaProducerHelper.startSpanAndInject(record);
        callback = KafkaProducerHelper.wrapCallback(callback, span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(
            @Advice.Thrown Throwable thrown,
            @Advice.Local("otelSpan") Span span) {

        if (thrown != null) {
            KafkaProducerHelper.endWithError(span, thrown);
        }
    }
}
