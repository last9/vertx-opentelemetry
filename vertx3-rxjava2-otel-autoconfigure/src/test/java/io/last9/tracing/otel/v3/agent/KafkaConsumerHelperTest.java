package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.Handler;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.impl.KafkaConsumerRecordImpl;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaConsumerHelperTest {

    private GlobalOtelTestSetup otel;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void setUp() {
        otel = new GlobalOtelTestSetup();
        otel.setUp();
        spanExporter = otel.getSpanExporter();
    }

    @AfterEach
    void tearDown() {
        otel.tearDown();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private KafkaConsumerRecord<String, String> createRecord(String topic, int partition,
                                                               long offset, String key,
                                                               String value) {
        ConsumerRecord<String, String> raw = new ConsumerRecord<>(topic, partition, offset, key, value);
        return new KafkaConsumerRecordImpl(raw);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private KafkaConsumerRecord<String, String> createRecordWithTraceparent(
            String topic, String traceparent) {
        ConsumerRecord<String, String> raw = new ConsumerRecord<>(topic, 0, 0L, "key", "value");
        raw.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        return new KafkaConsumerRecordImpl(raw);
    }

    @Test
    void wrapHandlerCreatesConsumerSpanPerRecord() {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        @SuppressWarnings("rawtypes")
        Handler<KafkaConsumerRecord> original = record ->
                capturedTraceId.set(Span.current().getSpanContext().getTraceId());

        @SuppressWarnings("rawtypes")
        Handler<KafkaConsumerRecord> wrapped = KafkaConsumerHelper.wrapHandler(original);

        wrapped.handle(createRecord("orders", 0, 42L, "key-1", "value-1"));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("orders process");
        assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.system")))
                .isEqualTo("kafka");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("orders");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.operation")))
                .isEqualTo("process");
        assertThat(span.getAttributes().get(AttributeKey.longKey("messaging.kafka.destination.partition")))
                .isEqualTo(0L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("messaging.kafka.message.offset")))
                .isEqualTo(42L);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.kafka.message.key")))
                .isEqualTo("key-1");

        // Trace ID was current inside the handler
        assertThat(capturedTraceId.get()).isEqualTo(span.getTraceId());
        assertThat(capturedTraceId.get()).isNotEqualTo("00000000000000000000000000000000");
    }

    @Test
    void wrapHandlerLinksToProducerSpan() {
        // Create a producer span to get a valid trace/span ID
        Span producerSpan = otel.getOpenTelemetry().getTracer("test")
                .spanBuilder("orders publish")
                .setSpanKind(SpanKind.PRODUCER)
                .startSpan();
        String producerTraceId = producerSpan.getSpanContext().getTraceId();
        String producerSpanId = producerSpan.getSpanContext().getSpanId();
        producerSpan.end();

        String traceparent = "00-" + producerTraceId + "-" + producerSpanId + "-01";

        @SuppressWarnings("rawtypes")
        Handler<KafkaConsumerRecord> wrapped = KafkaConsumerHelper.wrapHandler(record -> {});

        wrapped.handle(createRecordWithTraceparent("orders", traceparent));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData consumerSpan = spans.stream()
                .filter(s -> s.getKind() == SpanKind.CONSUMER)
                .findFirst().orElseThrow();

        // Consumer span is a root span (no parent)
        assertThat(consumerSpan.getParentSpanContext().isValid()).isFalse();

        // Consumer span links to the producer span
        assertThat(consumerSpan.getLinks()).hasSize(1);
        assertThat(consumerSpan.getLinks().get(0).getSpanContext().getTraceId())
                .isEqualTo(producerTraceId);
        assertThat(consumerSpan.getLinks().get(0).getSpanContext().getSpanId())
                .isEqualTo(producerSpanId);
    }

    @Test
    void wrapHandlerRecordsExceptionAndRethrows() {
        @SuppressWarnings("rawtypes")
        Handler<KafkaConsumerRecord> wrapped = KafkaConsumerHelper.wrapHandler(
                record -> { throw new RuntimeException("processing failed"); });

        assertThatThrownBy(() -> wrapped.handle(createRecord("orders", 0, 0L, "k", "v")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("processing failed");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void wrapHandlerReturnsNullForNullInput() {
        @SuppressWarnings("rawtypes")
        Handler<KafkaConsumerRecord> result = KafkaConsumerHelper.wrapHandler(null);
        assertThat(result).isNull();
    }

    @Test
    void wrapHandlerDelegatesCallsToOriginal() {
        AtomicBoolean called = new AtomicBoolean(false);

        @SuppressWarnings("rawtypes")
        Handler<KafkaConsumerRecord> original = record -> called.set(true);

        @SuppressWarnings("rawtypes")
        Handler<KafkaConsumerRecord> wrapped = KafkaConsumerHelper.wrapHandler(original);

        wrapped.handle(createRecord("events", 1, 10L, "k", "v"));

        assertThat(called.get()).isTrue();
    }

    @Test
    void multipleRecordsProduceSeparateSpans() {
        @SuppressWarnings("rawtypes")
        Handler<KafkaConsumerRecord> wrapped = KafkaConsumerHelper.wrapHandler(record -> {});

        wrapped.handle(createRecord("orders", 0, 0L, "k1", "v1"));
        wrapped.handle(createRecord("orders", 0, 1L, "k2", "v2"));
        wrapped.handle(createRecord("orders", 0, 2L, "k3", "v3"));

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(3);
    }
}
