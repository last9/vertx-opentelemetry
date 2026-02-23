package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.reactivex.Single;
import io.vertx.kafka.client.producer.RecordMetadata;
import io.vertx.reactivex.kafka.client.producer.KafkaProducer;
import io.vertx.reactivex.kafka.client.producer.KafkaProducerRecord;

/**
 * A wrapper around Vert.x 3 {@link KafkaProducer} that automatically creates an
 * OpenTelemetry PRODUCER span for every {@link #rxSend(KafkaProducerRecord)} call.
 *
 * <p>Vert.x 3 has no Kafka tracing SPI, so producer sends produce no spans and do not
 * carry trace context into Kafka headers automatically. {@code TracedKafkaProducer} solves both:
 * <ul>
 *   <li>Creates a PRODUCER span per OTel messaging semantic conventions with attributes:
 *       {@code messaging.system}, {@code messaging.destination.name},
 *       {@code messaging.operation}, {@code messaging.kafka.message.key},
 *       {@code messaging.kafka.destination.partition}, {@code messaging.kafka.message.offset}</li>
 *   <li>Injects {@code traceparent} into Kafka record headers so downstream consumers
 *       can link their spans to the producer trace</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Wrap an existing producer:
 * TracedKafkaProducer<String, String> traced = TracedKafkaProducer.wrap(kafkaProducer);
 *
 * // Every send automatically gets a PRODUCER span + context propagation:
 * KafkaProducerRecord<String, String> record =
 *         KafkaProducerRecord.create("orders", "key", "value");
 * traced.rxSend(record)
 *     .subscribe(metadata -> logger.info("Sent to partition {}", metadata.getPartition()));
 * }</pre>
 *
 * @param <K> Kafka record key type
 * @param <V> Kafka record value type
 * @see KafkaTracing#tracedSend(KafkaProducer, KafkaProducerRecord)
 * @see KafkaTracing#tracedBatchHandler(String, io.vertx.core.Handler)
 */
public final class TracedKafkaProducer<K, V> {

    private final KafkaProducer<K, V> delegate;
    private final OpenTelemetry openTelemetry;

    private TracedKafkaProducer(KafkaProducer<K, V> delegate, OpenTelemetry openTelemetry) {
        this.delegate = delegate;
        this.openTelemetry = openTelemetry;
    }

    /**
     * Wraps a {@link KafkaProducer} with automatic PRODUCER span creation using
     * {@link GlobalOpenTelemetry}.
     *
     * @param <K>      Kafka record key type
     * @param <V>      Kafka record value type
     * @param producer the Kafka producer to wrap
     * @return a traced producer that auto-creates PRODUCER spans on send
     */
    public static <K, V> TracedKafkaProducer<K, V> wrap(KafkaProducer<K, V> producer) {
        return new TracedKafkaProducer<>(producer, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps a {@link KafkaProducer} with automatic PRODUCER span creation using the supplied
     * {@link OpenTelemetry} instance. Useful in tests.
     *
     * @param <K>            Kafka record key type
     * @param <V>            Kafka record value type
     * @param producer       the Kafka producer to wrap
     * @param openTelemetry  the OpenTelemetry instance to use
     * @return a traced producer that auto-creates PRODUCER spans on send
     */
    public static <K, V> TracedKafkaProducer<K, V> wrap(KafkaProducer<K, V> producer,
                                                         OpenTelemetry openTelemetry) {
        return new TracedKafkaProducer<>(producer, openTelemetry);
    }

    /**
     * Sends a record to Kafka, wrapped with a PRODUCER span.
     *
     * <p>Equivalent to calling {@link KafkaTracing#tracedSend(KafkaProducer, KafkaProducerRecord, OpenTelemetry)}
     * with this producer's OpenTelemetry instance.
     *
     * @param record the record to send
     * @return a Single that emits {@link RecordMetadata} on success
     */
    public Single<RecordMetadata> rxSend(KafkaProducerRecord<K, V> record) {
        return KafkaTracing.tracedSend(delegate, record, openTelemetry);
    }

    /**
     * Returns the underlying unwrapped {@link KafkaProducer}.
     *
     * @return the delegate producer
     */
    public KafkaProducer<K, V> getDelegate() {
        return delegate;
    }

    /**
     * Closes the underlying Kafka producer.
     */
    public void close() {
        delegate.close();
    }
}
