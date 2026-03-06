package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.semconv.ExceptionAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.nio.charset.StandardCharsets;

/**
 * Helper methods called by {@link KafkaProducerAdvice} to create and manage
 * PRODUCER spans for raw {@code org.apache.kafka.clients.producer.KafkaProducer.send()}.
 *
 * <p>This intercepts at the kafka-clients library level, covering all usage patterns
 * (direct, Vert.x wrapper, any other framework). If the record already has a
 * {@code traceparent} header (injected by {@code KafkaTracing.tracedSend()} or
 * {@code TracedKafkaProducer}), the instrumentation is skipped to prevent double spans.
 */
public final class KafkaProducerHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    private KafkaProducerHelper() {}

    /**
     * Creates a PRODUCER span and injects traceparent into the record headers.
     * Returns null if the record is already traced (has traceparent header).
     */
    public static Span startSpanAndInject(ProducerRecord<?, ?> record) {
        // Idempotency: skip if already traced by TracedKafkaProducer/KafkaTracing
        if (record.headers().lastHeader("traceparent") != null) {
            return null;
        }

        OpenTelemetry otel = GlobalOpenTelemetry.get();
        Tracer tracer = otel.getTracer(TRACER_NAME);

        Span span = tracer.spanBuilder(record.topic() + " publish")
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "kafka")
                .setAttribute(SemanticAttributes.MESSAGING_DESTINATION_NAME, record.topic())
                .setAttribute(SemanticAttributes.MESSAGING_OPERATION,
                        SemanticAttributes.MessagingOperationValues.PUBLISH)
                .startSpan();

        if (record.key() != null) {
            span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_KEY,
                    String.valueOf(record.key()));
        }

        if (record.value() == null) {
            span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_TOMBSTONE, true);
        }

        if (record.partition() != null) {
            span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION,
                    (long) record.partition());
        }

        // Inject traceparent into record headers
        Context spanCtx = Context.current().with(span);
        otel.getPropagators().getTextMapPropagator()
                .inject(spanCtx, record.headers(),
                        (headers, key, value) ->
                                headers.add(key, value.getBytes(StandardCharsets.UTF_8)));

        return span;
    }

    /**
     * Wraps the original Callback (nullable) to end the span on completion.
     */
    public static Callback wrapCallback(Callback original, Span span) {
        if (span == null) {
            return original;
        }
        return (metadata, exception) -> {
            try {
                if (exception != null) {
                    span.recordException(exception,
                            Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                    span.setStatus(StatusCode.ERROR, exception.getMessage());
                } else if (metadata != null) {
                    span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION,
                            (long) metadata.partition());
                    span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET,
                            metadata.offset());
                }
            } finally {
                span.end();
            }
            if (original != null) {
                original.onCompletion(metadata, exception);
            }
        };
    }

    /**
     * Ends the span with an error (for synchronous exceptions from send()).
     */
    public static void endWithError(Span span, Throwable thrown) {
        if (span == null) return;
        span.recordException(thrown,
                Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
        span.setStatus(StatusCode.ERROR, thrown.getMessage());
        span.end();
    }
}
