package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.vertx.core.Handler;
import io.vertx.kafka.client.consumer.KafkaConsumerRecords;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.kafka.client.consumer.KafkaConsumer;

import java.util.Map;

/**
 * A wrapper around Vert.x 3 {@link KafkaConsumer} that automatically creates
 * OpenTelemetry CONSUMER spans for every batch of records received.
 *
 * <p>Vert.x 3 has no Kafka tracing SPI, so consumer poll callbacks produce no spans
 * and {@code trace_id} / {@code span_id} are empty in log lines. {@code TracedKafkaConsumer}
 * handles the full setup:
 * <ul>
 *   <li>Sets a traced {@code batchHandler} via {@link KafkaTracing#tracedBatchHandler}</li>
 *   <li>Sets the required no-op per-record {@code handler} (needed to start polling)</li>
 *   <li>Subscribes to the topic</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TracedKafkaConsumer.create(vertx, consumerConfig, "orders", "order-processors",
 *         records -> {
 *             for (int i = 0; i < records.size(); i++) {
 *                 logger.info("Record: {}", records.recordAt(i).value());
 *             }
 *         });
 * }</pre>
 *
 * <p>This replaces the manual boilerplate:
 * <pre>{@code
 * KafkaConsumer<K, V> consumer = KafkaConsumer.create(vertx, config);
 * consumer.getDelegate().batchHandler(
 *         KafkaTracing.tracedBatchHandler(topic, consumerGroup, handler, otel));
 * consumer.handler(record -> {});  // required to start polling
 * consumer.subscribe(topic);
 * }</pre>
 *
 * @param <K> Kafka record key type
 * @param <V> Kafka record value type
 * @see KafkaTracing#tracedBatchHandler(String, String, Handler, OpenTelemetry)
 * @see TracedKafkaProducer
 */
public final class TracedKafkaConsumer<K, V> {

    private final KafkaConsumer<K, V> delegate;

    private TracedKafkaConsumer(KafkaConsumer<K, V> delegate) {
        this.delegate = delegate;
    }

    /**
     * Creates a traced Kafka consumer that subscribes to a topic with automatic
     * CONSUMER span creation using {@link GlobalOpenTelemetry}.
     *
     * @param <K>           Kafka record key type
     * @param <V>           Kafka record value type
     * @param vertx         the Vert.x instance
     * @param config        Kafka consumer configuration (must include bootstrap.servers, deserializers, group.id)
     * @param topic         the topic to subscribe to
     * @param batchHandler  the handler invoked for each batch of records (runs inside a CONSUMER span)
     * @return a traced consumer that is already subscribed and polling
     */
    public static <K, V> TracedKafkaConsumer<K, V> create(
            Vertx vertx,
            Map<String, String> config,
            String topic,
            Handler<KafkaConsumerRecords<K, V>> batchHandler) {
        return create(vertx, config, topic, null, batchHandler, GlobalOpenTelemetry.get());
    }

    /**
     * Creates a traced Kafka consumer with consumer group in span attributes.
     *
     * @param <K>            Kafka record key type
     * @param <V>            Kafka record value type
     * @param vertx          the Vert.x instance
     * @param config         Kafka consumer configuration
     * @param topic          the topic to subscribe to
     * @param consumerGroup  the consumer group name (set as {@code messaging.kafka.consumer.group})
     * @param batchHandler   the handler invoked for each batch of records
     * @return a traced consumer that is already subscribed and polling
     */
    public static <K, V> TracedKafkaConsumer<K, V> create(
            Vertx vertx,
            Map<String, String> config,
            String topic,
            String consumerGroup,
            Handler<KafkaConsumerRecords<K, V>> batchHandler) {
        return create(vertx, config, topic, consumerGroup, batchHandler, GlobalOpenTelemetry.get());
    }

    /**
     * Creates a traced Kafka consumer with explicit {@link OpenTelemetry} instance.
     *
     * @param <K>            Kafka record key type
     * @param <V>            Kafka record value type
     * @param vertx          the Vert.x instance
     * @param config         Kafka consumer configuration
     * @param topic          the topic to subscribe to
     * @param consumerGroup  the consumer group name (nullable)
     * @param batchHandler   the handler invoked for each batch of records
     * @param openTelemetry  the OpenTelemetry instance to use
     * @return a traced consumer that is already subscribed and polling
     */
    public static <K, V> TracedKafkaConsumer<K, V> create(
            Vertx vertx,
            Map<String, String> config,
            String topic,
            String consumerGroup,
            Handler<KafkaConsumerRecords<K, V>> batchHandler,
            OpenTelemetry openTelemetry) {

        KafkaConsumer<K, V> consumer = KafkaConsumer.create(vertx, config);

        // Set traced batch handler on the core delegate (KafkaTracing uses core Vert.x types)
        consumer.getDelegate().batchHandler(
                KafkaTracing.tracedBatchHandler(topic, consumerGroup, batchHandler, openTelemetry));

        // A per-record handler MUST be set for Vert.x to start polling.
        // The batch handler receives all records; this is a no-op.
        consumer.handler(record -> {});

        // Subscribe to the topic
        consumer.subscribe(topic);

        return new TracedKafkaConsumer<>(consumer);
    }

    /**
     * Returns the underlying {@link KafkaConsumer} for additional configuration
     * (e.g., setting partition handlers, seeking offsets).
     *
     * @return the delegate consumer
     */
    public KafkaConsumer<K, V> getDelegate() {
        return delegate;
    }

    /**
     * Closes the underlying Kafka consumer.
     */
    public void close() {
        delegate.close();
    }
}
