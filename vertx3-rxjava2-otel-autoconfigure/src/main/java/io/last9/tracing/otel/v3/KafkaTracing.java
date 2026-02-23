package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.SemanticAttributes;
import io.vertx.core.Handler;
import io.vertx.kafka.client.consumer.KafkaConsumerRecords;

/**
 * Utility for adding OpenTelemetry tracing to Vert.x 3 Kafka batch consumers.
 *
 * <p>Vert.x 3 has no {@code VertxTracer} SPI, so Kafka consumer handlers receive no span
 * context at all. Without instrumentation, {@link Span#current()} returns the no-op root span
 * inside a batch handler, leaving {@code trace_id} and {@code span_id} empty in every log line
 * produced during message processing.
 *
 * <p>{@code KafkaTracing.tracedBatchHandler()} wraps your existing
 * {@link io.vertx.kafka.client.consumer.KafkaConsumer#batchHandler(Handler)} callback with a
 * CONSUMER span. The span is made current for the duration of the handler, so
 * {@link io.last9.tracing.otel.MdcTraceTurboFilter} can inject {@code trace_id}/{@code span_id}
 * into every log line produced by the handler, and all RxJava chains assembled inside the handler
 * capture the correct context via {@link RxJava2ContextPropagation}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * import io.last9.tracing.otel.v3.KafkaTracing;
 *
 * // In your verticle's start() method:
 * consumer.batchHandler(KafkaTracing.tracedBatchHandler(topicName, this::handleBatch));
 *
 * private void handleBatch(KafkaConsumerRecords<String, String> records) {
 *     // Span.current() is now the CONSUMER span — trace_id appears in logs
 *     logger.info("Processing {} records", records.size());
 *     ...
 * }
 * }</pre>
 *
 * @see RxJava2ContextPropagation
 * @see OtelLauncher
 */
public final class KafkaTracing {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    private KafkaTracing() {
        // Utility class
    }

    /**
     * Wraps a Kafka batch handler with an OpenTelemetry CONSUMER span.
     *
     * <p>Uses {@link GlobalOpenTelemetry#get()} to obtain the tracer — suitable for production
     * use after {@link OtelLauncher} has initialised the SDK.
     *
     * @param <K>      Kafka record key type
     * @param <V>      Kafka record value type
     * @param topic    the topic name, set as {@code messaging.destination.name} on the span
     * @param delegate the original batch handler
     * @return a wrapped handler that creates and ends a CONSUMER span around each batch
     */
    public static <K, V> Handler<KafkaConsumerRecords<K, V>> tracedBatchHandler(
            String topic,
            Handler<KafkaConsumerRecords<K, V>> delegate) {
        return tracedBatchHandler(topic, delegate, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps a Kafka batch handler with an OpenTelemetry CONSUMER span using the supplied
     * {@link OpenTelemetry} instance. Useful when the SDK is not registered globally
     * (e.g., in tests that construct their own {@code OpenTelemetrySdk}).
     *
     * @param <K>            Kafka record key type
     * @param <V>            Kafka record value type
     * @param topic          the topic name, set as {@code messaging.destination.name} on the span
     * @param delegate       the original batch handler
     * @param openTelemetry  the OpenTelemetry instance to use
     * @return a wrapped handler that creates and ends a CONSUMER span around each batch
     */
    public static <K, V> Handler<KafkaConsumerRecords<K, V>> tracedBatchHandler(
            String topic,
            Handler<KafkaConsumerRecords<K, V>> delegate,
            OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        return records -> {
            Span span = tracer.spanBuilder("kafka.consume.batch")
                    .setSpanKind(SpanKind.CONSUMER)
                    .setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "kafka")
                    .setAttribute(SemanticAttributes.MESSAGING_DESTINATION_NAME, topic)
                    .setAttribute(SemanticAttributes.MESSAGING_BATCH_MESSAGE_COUNT, (long) records.size())
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
                delegate.handle(records);
            } catch (Throwable t) {
                span.recordException(t);
                span.setStatus(StatusCode.ERROR, t.getMessage());
                throw t;
            } finally {
                span.end();
            }
        };
    }
}
