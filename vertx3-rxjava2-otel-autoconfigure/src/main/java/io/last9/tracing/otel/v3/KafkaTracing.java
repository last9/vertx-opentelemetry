package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.semconv.SemanticAttributes;
import io.reactivex.Single;
import io.vertx.core.Handler;
import io.vertx.kafka.client.consumer.KafkaConsumerRecords;
import io.vertx.kafka.client.producer.RecordMetadata;
import io.vertx.reactivex.kafka.client.producer.KafkaProducer;
import io.vertx.reactivex.kafka.client.producer.KafkaProducerRecord;

/**
 * Utility for adding OpenTelemetry tracing to Vert.x 3 Kafka producers and consumers.
 *
 * <p>Vert.x 3 has no {@code VertxTracer} SPI, so Kafka operations produce no spans
 * automatically. This utility provides both PRODUCER and CONSUMER span creation
 * following OTel messaging semantic conventions.
 *
 * <h2>Producer tracing</h2>
 * <p>{@code KafkaTracing.tracedSend()} wraps a single {@code KafkaProducer.rxSend()} call
 * with a PRODUCER span. It also injects the trace context into Kafka record headers so
 * downstream consumers can link their spans to the producer trace:
 * <pre>{@code
 * KafkaProducerRecord<String, String> record =
 *         KafkaProducerRecord.create("orders", "key", "value");
 *
 * KafkaTracing.tracedSend(producer, record)
 *     .subscribe(metadata -> logger.info("Sent to partition {}", metadata.getPartition()));
 * }</pre>
 *
 * <h2>Consumer tracing</h2>
 * <p>{@code KafkaTracing.tracedBatchHandler()} wraps your existing
 * {@link io.vertx.kafka.client.consumer.KafkaConsumer#batchHandler(Handler)} callback with a
 * CONSUMER span:
 * <pre>{@code
 * consumer.batchHandler(KafkaTracing.tracedBatchHandler(topicName, this::handleBatch));
 * }</pre>
 *
 * @see TracedKafkaProducer
 * @see RxJava2ContextPropagation
 * @see OtelLauncher
 */
public final class KafkaTracing {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    /**
     * {@link TextMapSetter} that injects trace context into Kafka record headers.
     * Enables consumer spans to link back to the producer trace.
     */
    @SuppressWarnings("rawtypes")
    private static final TextMapSetter<KafkaProducerRecord> RECORD_SETTER =
            (record, key, value) -> record.addHeader(key, value);

    private KafkaTracing() {
        // Utility class
    }

    // ---- Consumer tracing ----

    /**
     * Wraps a Kafka batch handler with an OpenTelemetry CONSUMER span.
     *
     * <p>Uses {@link GlobalOpenTelemetry#get()} to obtain the tracer — suitable for production
     * use after {@link OtelLauncher} has initialised the SDK.
     *
     * <p>Span name follows OTel convention: {@code {topic} process}.
     *
     * <p>Attributes set:
     * <ul>
     *   <li>{@code messaging.system} = "kafka"</li>
     *   <li>{@code messaging.destination.name} = topic</li>
     *   <li>{@code messaging.operation} = "process"</li>
     *   <li>{@code messaging.batch.message_count} = batch size</li>
     *   <li>{@code messaging.kafka.consumer.group} = consumer group (if provided)</li>
     * </ul>
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
        return tracedBatchHandler(topic, null, delegate, openTelemetry);
    }

    /**
     * Wraps a Kafka batch handler with an OpenTelemetry CONSUMER span, including
     * the consumer group in span attributes.
     *
     * @param <K>            Kafka record key type
     * @param <V>            Kafka record value type
     * @param topic          the topic name
     * @param consumerGroup  the consumer group name (nullable)
     * @param delegate       the original batch handler
     * @param openTelemetry  the OpenTelemetry instance to use
     * @return a wrapped handler that creates and ends a CONSUMER span around each batch
     */
    public static <K, V> Handler<KafkaConsumerRecords<K, V>> tracedBatchHandler(
            String topic,
            String consumerGroup,
            Handler<KafkaConsumerRecords<K, V>> delegate,
            OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        return records -> {
            var spanBuilder = tracer.spanBuilder(topic + " process")
                    .setSpanKind(SpanKind.CONSUMER)
                    .setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "kafka")
                    .setAttribute(SemanticAttributes.MESSAGING_DESTINATION_NAME, topic)
                    .setAttribute(SemanticAttributes.MESSAGING_OPERATION,
                            SemanticAttributes.MessagingOperationValues.PROCESS)
                    .setAttribute(SemanticAttributes.MESSAGING_BATCH_MESSAGE_COUNT,
                            (long) records.size());

            if (consumerGroup != null) {
                spanBuilder.setAttribute(SemanticAttributes.MESSAGING_KAFKA_CONSUMER_GROUP,
                        consumerGroup);
            }

            Span span = spanBuilder.startSpan();
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

    // ---- Producer tracing ----

    /**
     * Wraps a single Kafka producer send with an OpenTelemetry PRODUCER span.
     *
     * <p>The span follows OTel messaging semantic conventions:
     * <ul>
     *   <li>{@code messaging.system} = "kafka"</li>
     *   <li>{@code messaging.destination.name} = topic</li>
     *   <li>{@code messaging.operation} = "publish"</li>
     *   <li>{@code messaging.kafka.message.key} = record key (if non-null)</li>
     *   <li>{@code messaging.kafka.destination.partition} = partition (set after send)</li>
     *   <li>{@code messaging.kafka.message.offset} = offset (set after send)</li>
     * </ul>
     *
     * <p>Trace context is injected into the Kafka record headers via W3C {@code traceparent},
     * enabling downstream consumers to link their spans to this producer trace.
     *
     * <p>Uses {@link GlobalOpenTelemetry#get()} to obtain the tracer.
     *
     * @param <K>      Kafka record key type
     * @param <V>      Kafka record value type
     * @param producer the Kafka producer to send with
     * @param record   the record to send
     * @return a Single that emits {@link RecordMetadata} on success, wrapped with a PRODUCER span
     */
    public static <K, V> Single<RecordMetadata> tracedSend(
            KafkaProducer<K, V> producer,
            KafkaProducerRecord<K, V> record) {
        return tracedSend(producer, record, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps a single Kafka producer send with an OpenTelemetry PRODUCER span using the supplied
     * {@link OpenTelemetry} instance.
     *
     * @param <K>            Kafka record key type
     * @param <V>            Kafka record value type
     * @param producer       the Kafka producer to send with
     * @param record         the record to send
     * @param openTelemetry  the OpenTelemetry instance to use
     * @return a Single that emits {@link RecordMetadata} on success, wrapped with a PRODUCER span
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Single<RecordMetadata> tracedSend(
            KafkaProducer<K, V> producer,
            KafkaProducerRecord<K, V> record,
            OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
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

        // Check for tombstone (null value)
        if (record.value() == null) {
            span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_TOMBSTONE, true);
        }

        // If a specific partition was requested, set it before send
        if (record.partition() != null) {
            span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION,
                    (long) record.partition());
        }

        // Inject trace context into Kafka record headers for consumer correlation
        Context spanContext = Context.current().with(span);
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(spanContext, record, RECORD_SETTER);

        try (Scope ignored = span.makeCurrent()) {
            return producer.rxSend(record)
                    .doOnSuccess(metadata -> {
                        span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION,
                                (long) metadata.getPartition());
                        span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET,
                                metadata.getOffset());
                        span.end();
                    })
                    .doOnError(err -> {
                        span.recordException(err);
                        span.setStatus(StatusCode.ERROR, err.getMessage());
                        span.end();
                    });
        }
    }
}
