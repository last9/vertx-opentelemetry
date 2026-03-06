package io.last9.tracing.otel.v3.agent;

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
import io.opentelemetry.semconv.ExceptionAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import io.vertx.core.Handler;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.KafkaHeader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Helper methods called by {@link KafkaConsumerAdvice} to wrap per-record handlers
 * with CONSUMER spans.
 *
 * <p>Supports two interception modes:
 * <ul>
 *   <li>{@link #wrapRawHandler(Handler)} — intercepts at KafkaReadStreamImpl level
 *       where records are raw {@code ConsumerRecord<K,V>} (used by ByteBuddy agent)</li>
 *   <li>{@link #wrapHandler(Handler)} — intercepts at Vert.x wrapper level where
 *       records are {@code KafkaConsumerRecord} (used by unit tests)</li>
 * </ul>
 */
public final class KafkaConsumerHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    /** Guard against wrapping the same handler more than once. */
    private static final Set<Handler<?>> WRAPPED_HANDLERS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    /** TextMapGetter for Vert.x KafkaConsumerRecord (used in wrapHandler). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final TextMapGetter<KafkaConsumerRecord> RECORD_GETTER =
            new TextMapGetter<KafkaConsumerRecord>() {
                @Override
                public Iterable<String> keys(KafkaConsumerRecord record) {
                    List<KafkaHeader> headers = record.headers();
                    if (headers == null || headers.isEmpty()) return Collections.emptyList();
                    List<String> keys = new java.util.ArrayList<>(headers.size());
                    for (KafkaHeader h : headers) {
                        keys.add(h.key());
                    }
                    return keys;
                }

                @Override
                public String get(KafkaConsumerRecord record, String key) {
                    if (record == null) return null;
                    List<KafkaHeader> headers = record.headers();
                    if (headers == null) return null;
                    for (KafkaHeader h : headers) {
                        if (key.equals(h.key())) {
                            return new String(h.value().getBytes(), StandardCharsets.UTF_8);
                        }
                    }
                    return null;
                }
            };

    /** TextMapGetter for raw Kafka ConsumerRecord (used in wrapRawHandler). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final TextMapGetter<ConsumerRecord> RAW_RECORD_GETTER =
            new TextMapGetter<ConsumerRecord>() {
                @Override
                public Iterable<String> keys(ConsumerRecord record) {
                    if (record.headers() == null) return Collections.emptyList();
                    List<String> keys = new java.util.ArrayList<>();
                    for (Header h : record.headers()) {
                        keys.add(h.key());
                    }
                    return keys;
                }

                @Override
                public String get(ConsumerRecord record, String key) {
                    if (record == null || record.headers() == null) return null;
                    Header header = record.headers().lastHeader(key);
                    if (header == null || header.value() == null) return null;
                    return new String(header.value(), StandardCharsets.UTF_8);
                }
            };

    private KafkaConsumerHelper() {}

    /**
     * Wraps a raw Kafka ConsumerRecord handler (from KafkaReadStreamImpl) with CONSUMER spans.
     * This is called by ByteBuddy agent advice.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Handler wrapRawHandler(Handler original) {
        if (original == null || WRAPPED_HANDLERS.contains(original)) {
            return original;
        }

        Handler wrapped = record -> {
            if (!(record instanceof ConsumerRecord)) {
                original.handle(record);
                return;
            }

            ConsumerRecord cr = (ConsumerRecord) record;
            OpenTelemetry otel = GlobalOpenTelemetry.get();
            Tracer tracer = otel.getTracer(TRACER_NAME);

            String topic = cr.topic() != null ? cr.topic() : "unknown";

            var spanBuilder = tracer.spanBuilder(topic + " process")
                    .setParent(Context.root())
                    .setSpanKind(SpanKind.CONSUMER)
                    .setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "kafka")
                    .setAttribute(SemanticAttributes.MESSAGING_DESTINATION_NAME, topic)
                    .setAttribute(SemanticAttributes.MESSAGING_OPERATION,
                            SemanticAttributes.MessagingOperationValues.PROCESS)
                    .setAttribute(SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION,
                            (long) cr.partition())
                    .setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET,
                            cr.offset());

            if (cr.key() != null) {
                spanBuilder.setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_KEY,
                        String.valueOf(cr.key()));
            }

            // Link to producer span via traceparent header
            Context producerCtx = otel.getPropagators().getTextMapPropagator()
                    .extract(Context.root(), cr, RAW_RECORD_GETTER);
            SpanContext producerSpanCtx = Span.fromContext(producerCtx).getSpanContext();
            if (producerSpanCtx.isValid()) {
                spanBuilder.addLink(producerSpanCtx);
            }

            Span span = spanBuilder.startSpan();
            try (Scope ignored = span.makeCurrent()) {
                original.handle(record);
            } catch (Throwable t) {
                span.recordException(t,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, t.getMessage());
                throw t;
            } finally {
                span.end();
            }
        };

        WRAPPED_HANDLERS.add(wrapped);
        return wrapped;
    }

    /**
     * Wraps a Vert.x KafkaConsumerRecord handler with CONSUMER spans.
     * Used by unit tests and applications using Vert.x KafkaConsumerRecord API directly.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Handler<KafkaConsumerRecord> wrapHandler(Handler<KafkaConsumerRecord> original) {
        if (original == null || WRAPPED_HANDLERS.contains(original)) {
            return original;
        }

        Handler<KafkaConsumerRecord> wrapped = record -> {
            OpenTelemetry otel = GlobalOpenTelemetry.get();
            Tracer tracer = otel.getTracer(TRACER_NAME);

            String topic = record.topic() != null ? record.topic() : "unknown";

            var spanBuilder = tracer.spanBuilder(topic + " process")
                    .setParent(Context.root())
                    .setSpanKind(SpanKind.CONSUMER)
                    .setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "kafka")
                    .setAttribute(SemanticAttributes.MESSAGING_DESTINATION_NAME, topic)
                    .setAttribute(SemanticAttributes.MESSAGING_OPERATION,
                            SemanticAttributes.MessagingOperationValues.PROCESS)
                    .setAttribute(SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION,
                            (long) record.partition())
                    .setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_OFFSET,
                            record.offset());

            if (record.key() != null) {
                spanBuilder.setAttribute(SemanticAttributes.MESSAGING_KAFKA_MESSAGE_KEY,
                        String.valueOf(record.key()));
            }

            // Link to producer span via traceparent header
            Context producerCtx = otel.getPropagators().getTextMapPropagator()
                    .extract(Context.root(), (KafkaConsumerRecord) record, RECORD_GETTER);
            SpanContext producerSpanCtx = Span.fromContext(producerCtx).getSpanContext();
            if (producerSpanCtx.isValid()) {
                spanBuilder.addLink(producerSpanCtx);
            }

            Span span = spanBuilder.startSpan();
            try (Scope ignored = span.makeCurrent()) {
                original.handle(record);
            } catch (Throwable t) {
                span.recordException(t,
                        Attributes.of(ExceptionAttributes.EXCEPTION_ESCAPED, true));
                span.setStatus(StatusCode.ERROR, t.getMessage());
                throw t;
            } finally {
                span.end();
            }
        };

        WRAPPED_HANDLERS.add(wrapped);
        return wrapped;
    }
}
