package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.semconv.ExceptionAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import io.reactivex.Single;
import io.vertx.core.Handler;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.KafkaConsumerRecords;
import io.vertx.kafka.client.producer.KafkaHeader;
import io.vertx.kafka.client.producer.RecordMetadata;
import io.vertx.reactivex.kafka.client.consumer.KafkaConsumer;
import io.vertx.reactivex.kafka.client.producer.KafkaProducer;
import io.vertx.reactivex.kafka.client.producer.KafkaProducerRecord;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

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

    /**
     * {@link TextMapGetter} that extracts trace context from Kafka consumer record headers.
     * Used to extract the producer's {@code traceparent} and add it as a
     * {@link io.opentelemetry.api.trace.SpanContext} link on the CONSUMER span.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final TextMapGetter<KafkaConsumerRecord> RECORD_GETTER =
            new TextMapGetter<KafkaConsumerRecord>() {
                @Override
                public Iterable<String> keys(KafkaConsumerRecord record) {
                    List<String> keys = new java.util.ArrayList<>();
                    for (KafkaHeader h : (List<KafkaHeader>) record.headers()) {
                        keys.add(h.key());
                    }
                    return keys;
                }

                @Override
                public String get(KafkaConsumerRecord record, String key) {
                    if (record == null) return null;
                    for (KafkaHeader h : (List<KafkaHeader>) record.headers()) {
                        if (key.equals(h.key())) {
                            return new String(h.value().getBytes(), StandardCharsets.UTF_8);
                        }
                    }
                    return null;
                }
            };

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
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <K, V> Handler<KafkaConsumerRecords<K, V>> tracedBatchHandler(
            String topic,
            String consumerGroup,
            Handler<KafkaConsumerRecords<K, V>> delegate,
            OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        return records -> {
            // Use Context.root() as parent to prevent leaked HTTP SERVER span context from
            // polluting Kafka consumer spans (same fix as TracedRouter for HTTP handlers).
            // Consumer spans are root spans per OTel messaging semconv — they link to producers
            // via SpanLink rather than parent/child to model the async relationship accurately.
            var spanBuilder = tracer.spanBuilder(topic + " process")
                    .setParent(Context.root())
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

            // Add a SpanLink for each record that carries a traceparent header.
            // This connects the consumer span to the producer span(s) without making it
            // a child — correct for async messaging where producer and consumer are decoupled.
            for (int i = 0; i < records.size(); i++) {
                KafkaConsumerRecord record = records.recordAt(i);
                Context producerCtx = openTelemetry.getPropagators().getTextMapPropagator()
                        .extract(Context.root(), record, RECORD_GETTER);
                SpanContext producerSpanCtx = Span.fromContext(producerCtx).getSpanContext();
                if (producerSpanCtx.isValid()) {
                    spanBuilder.addLink(producerSpanCtx);
                }
            }

            Span span = spanBuilder.startSpan();
            try (Scope ignored = span.makeCurrent()) {
                delegate.handle(records);
            } catch (Throwable t) {
                span.recordException(t,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, t.getMessage());
                throw t;
            } finally {
                span.end();
            }
        };
    }

    // ---- Consumer setup convenience methods ----

    /**
     * Wires a traced Kafka consumer with a single call using {@link GlobalOpenTelemetry}.
     *
     * <p>Equivalent to the following boilerplate:
     * <pre>{@code
     * consumer.getDelegate().batchHandler(
     *         KafkaTracing.tracedBatchHandler(topic, batchHandler));
     * consumer.exceptionHandler(KafkaTracing.tracedExceptionHandler(topic));
     * consumer.handler(record -> {});  // required to start polling
     * consumer.subscribe(topic);
     * }</pre>
     *
     * @param <K>          Kafka record key type
     * @param <V>          Kafka record value type
     * @param consumer     the Kafka consumer to configure
     * @param topic        the topic to subscribe to
     * @param batchHandler the handler invoked for each batch of records
     */
    public static <K, V> void setupConsumer(
            KafkaConsumer<K, V> consumer,
            String topic,
            Handler<KafkaConsumerRecords<K, V>> batchHandler) {
        setupConsumer(consumer, topic, null, batchHandler, GlobalOpenTelemetry.get());
    }

    /**
     * Wires a traced Kafka consumer with consumer group in span attributes using
     * {@link GlobalOpenTelemetry}.
     *
     * @param <K>           Kafka record key type
     * @param <V>           Kafka record value type
     * @param consumer      the Kafka consumer to configure
     * @param topic         the topic to subscribe to
     * @param consumerGroup the consumer group name (set as {@code messaging.kafka.consumer.group})
     * @param batchHandler  the handler invoked for each batch of records
     */
    public static <K, V> void setupConsumer(
            KafkaConsumer<K, V> consumer,
            String topic,
            String consumerGroup,
            Handler<KafkaConsumerRecords<K, V>> batchHandler) {
        setupConsumer(consumer, topic, consumerGroup, batchHandler, GlobalOpenTelemetry.get());
    }

    /**
     * Wires a traced Kafka consumer with explicit {@link OpenTelemetry} instance.
     *
     * <p>Sets up all four required pieces in one call:
     * <ol>
     *   <li>A traced batch handler (CONSUMER span per poll)</li>
     *   <li>A traced exception handler (ERROR span for infrastructure errors)</li>
     *   <li>A no-op per-record handler (required by Vert.x to start polling)</li>
     *   <li>Subscription to the topic</li>
     * </ol>
     *
     * @param <K>            Kafka record key type
     * @param <V>            Kafka record value type
     * @param consumer       the Kafka consumer to configure
     * @param topic          the topic to subscribe to
     * @param consumerGroup  the consumer group name (nullable)
     * @param batchHandler   the handler invoked for each batch of records
     * @param openTelemetry  the OpenTelemetry instance to use
     */
    public static <K, V> void setupConsumer(
            KafkaConsumer<K, V> consumer,
            String topic,
            String consumerGroup,
            Handler<KafkaConsumerRecords<K, V>> batchHandler,
            OpenTelemetry openTelemetry) {
        consumer.getDelegate().batchHandler(
                tracedBatchHandler(topic, consumerGroup, batchHandler, openTelemetry));
        consumer.exceptionHandler(tracedExceptionHandler(topic, openTelemetry));
        consumer.handler(record -> {});
        consumer.subscribe(topic);
    }

    // ---- Consumer exception handler ----

    /**
     * Returns a Vert.x {@code Handler<Throwable>} suitable for use with
     * {@link io.vertx.reactivex.kafka.client.consumer.KafkaConsumer#exceptionHandler}.
     *
     * <p>Infrastructure-level consumer errors (broker unreachable, deserialization failures,
     * authentication errors) are delivered via the exception handler, NOT via the batch handler.
     * Without this, those errors produce no span and no log correlation.
     *
     * <p>For each error, this handler creates a short-lived CONSUMER span with:
     * <ul>
     *   <li>{@code messaging.system} = "kafka"</li>
     *   <li>{@code messaging.destination.name} = topic</li>
     *   <li>span status = ERROR</li>
     *   <li>exception event with the full stack trace</li>
     * </ul>
     *
     * @param topic         the topic the consumer is subscribed to
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a {@code Handler<Throwable>} that creates an ERROR span for each consumer error
     */
    public static Handler<Throwable> tracedExceptionHandler(
            String topic,
            OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        return err -> {
            Span span = tracer.spanBuilder(topic + " error")
                    .setSpanKind(SpanKind.CONSUMER)
                    .setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "kafka")
                    .setAttribute(SemanticAttributes.MESSAGING_DESTINATION_NAME, topic)
                    .setAttribute(SemanticAttributes.MESSAGING_OPERATION,
                            SemanticAttributes.MessagingOperationValues.PROCESS)
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
                span.recordException(err,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, err.getMessage());
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
        return tracedSend(record, producer.rxSend(record), openTelemetry);
    }

    /**
     * Package-private overload that accepts a pre-built {@code Single<RecordMetadata>}.
     * Separates span lifecycle from the producer call, making the logic testable without
     * a real Kafka broker.
     */
    @SuppressWarnings("unchecked")
    static <K, V> Single<RecordMetadata> tracedSend(
            KafkaProducerRecord<K, V> record,
            Single<RecordMetadata> sendSingle,
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

        if (record.value() == null) {
            span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_TOMBSTONE, true);
        }

        if (record.partition() != null) {
            span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION,
                    (long) record.partition());
        }

        // Inject trace context into record headers so consumers can link their spans
        Context spanContext = Context.current().with(span);
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(spanContext, record, RECORD_SETTER);

        try (Scope ignored = span.makeCurrent()) {
            return sendSingle
                    .doOnSuccess(metadata -> {
                        // Set partition/offset attributes once confirmed by the broker
                        span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION,
                                (long) metadata.getPartition());
                        span.setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET,
                                metadata.getOffset());
                    })
                    .doOnError(err -> {
                        span.recordException(err,
                                Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                        span.setStatus(StatusCode.ERROR, err.getMessage());
                    })
                    // doFinally guarantees span.end() on success, error, AND disposal/cancellation
                    .doFinally(span::end);
        } catch (Throwable t) {
            // sendSingle construction itself threw (e.g. closed producer, serialization error)
            span.recordException(t,
                    Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
            span.setStatus(StatusCode.ERROR, t.getMessage());
            span.end();
            throw t;
        }
    }
}
