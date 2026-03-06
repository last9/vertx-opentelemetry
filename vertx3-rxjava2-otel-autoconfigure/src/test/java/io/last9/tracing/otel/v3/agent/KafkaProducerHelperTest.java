package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaProducerHelperTest {

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

    @Test
    void startSpanAndInjectCreatesProducerSpan() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key-1", "value-1");
        Span span = KafkaProducerHelper.startSpanAndInject(record);

        assertThat(span).isNotNull();
        span.end();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getName()).isEqualTo("orders publish");
        assertThat(sd.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("messaging.system")))
                .isEqualTo("kafka");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("orders");
    }

    @Test
    void startSpanAndInjectInjectsTraceparentHeader() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key-1", "value-1");
        Span span = KafkaProducerHelper.startSpanAndInject(record);

        assertThat(span).isNotNull();
        assertThat(record.headers().lastHeader("traceparent")).isNotNull();
        span.end();
    }

    @Test
    void startSpanAndInjectReturnsNullIfAlreadyTraced() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key-1", "value-1");
        record.headers().add("traceparent", "00-abc-def-01".getBytes(StandardCharsets.UTF_8));

        Span span = KafkaProducerHelper.startSpanAndInject(record);

        assertThat(span).isNull();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void startSpanAndInjectSetsTombstoneAttribute() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key-1", null);
        Span span = KafkaProducerHelper.startSpanAndInject(record);

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.booleanKey("messaging.kafka.message.tombstone")))
                .isTrue();
    }

    @Test
    void startSpanAndInjectSetsMessageKey() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "order-123", "data");
        Span span = KafkaProducerHelper.startSpanAndInject(record);

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("messaging.kafka.message.key")))
                .isEqualTo("order-123");
    }

    @Test
    void wrapCallbackEndsSpanOnSuccess() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key", "value");
        Span span = KafkaProducerHelper.startSpanAndInject(record);

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("orders", 2), 0L, 42, -1L, null, -1, -1);

        Callback wrapped = KafkaProducerHelper.wrapCallback(null, span);
        wrapped.onCompletion(metadata, null);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getAttributes().get(AttributeKey.longKey("messaging.kafka.destination.partition")))
                .isEqualTo(2L);
        assertThat(sd.getAttributes().get(AttributeKey.longKey("messaging.kafka.message.offset")))
                .isEqualTo(42L);
    }

    @Test
    void wrapCallbackEndsSpanOnError() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key", "value");
        Span span = KafkaProducerHelper.startSpanAndInject(record);

        Callback wrapped = KafkaProducerHelper.wrapCallback(null, span);
        wrapped.onCompletion(null, new RuntimeException("broker down"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void wrapCallbackDelegatesToOriginal() {
        AtomicBoolean called = new AtomicBoolean(false);
        Callback original = (metadata, exception) -> called.set(true);

        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key", "value");
        Span span = KafkaProducerHelper.startSpanAndInject(record);

        Callback wrapped = KafkaProducerHelper.wrapCallback(original, span);
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("orders", 0), 0L, 0, -1L, null, -1, -1);
        wrapped.onCompletion(metadata, null);

        assertThat(called.get()).isTrue();
    }

    @Test
    void wrapCallbackReturnsOriginalWhenSpanIsNull() {
        Callback original = (metadata, exception) -> {};
        Callback result = KafkaProducerHelper.wrapCallback(original, null);

        assertThat(result).isSameAs(original);
    }

    @Test
    void endWithErrorHandlesNullSpan() {
        // Should not throw
        KafkaProducerHelper.endWithError(null, new RuntimeException("error"));
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void endWithErrorRecordsExceptionOnSpan() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key", "value");
        Span span = KafkaProducerHelper.startSpanAndInject(record);

        KafkaProducerHelper.endWithError(span, new RuntimeException("send failed"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getStatus().getDescription()).isEqualTo("send failed");
    }
}
