package io.last9.tracing.otel.v3;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.Handler;
import io.vertx.kafka.client.consumer.KafkaConsumerRecords;
import io.vertx.kafka.client.consumer.impl.KafkaConsumerRecordsImpl;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaTracingTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void setUp() {
        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();
    }

    @AfterEach
    void tearDown() {
        otel.shutdown();
    }

    // ---- Consumer (batch handler) tests ----

    @Test
    void batchHandlerCreatesConsumerSpan() {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Handler<KafkaConsumerRecords<String, String>> traced = KafkaTracing.tracedBatchHandler(
                "orders",
                records -> capturedTraceId.set(Span.current().getSpanContext().getTraceId()),
                otel.getOpenTelemetry()
        );

        traced.handle(emptyBatch());

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);

        // OTel convention: span name = "{destination} {operation}"
        assertThat(span.getName()).isEqualTo("orders process");
        assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);

        // Span was current inside the handler — trace_id was non-zero
        assertThat(capturedTraceId.get()).matches("[0-9a-f]{32}");
        assertThat(capturedTraceId.get()).isNotEqualTo("00000000000000000000000000000000");

        // trace_id inside handler matches the exported span
        assertThat(capturedTraceId.get()).isEqualTo(span.getTraceId());
    }

    @Test
    void batchHandlerSetsMessagingAttributes() {
        String topic = "payments";
        ConsumerRecords<String, String> rawRecords = recordsWithSize(topic, 3);

        Handler<KafkaConsumerRecords<String, String>> traced = KafkaTracing.tracedBatchHandler(
                topic,
                records -> {},
                otel.getOpenTelemetry()
        );

        traced.handle(new KafkaConsumerRecordsImpl<>(rawRecords));

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.system")))
                .isEqualTo("kafka");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo(topic);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.operation")))
                .isEqualTo("process");
        assertThat(span.getAttributes().get(AttributeKey.longKey("messaging.batch.message_count")))
                .isEqualTo(3L);
    }

    @Test
    void batchHandlerSetsConsumerGroupWhenProvided() {
        Handler<KafkaConsumerRecords<String, String>> traced = KafkaTracing.tracedBatchHandler(
                "orders",
                "order-processors",
                records -> {},
                otel.getOpenTelemetry()
        );

        traced.handle(emptyBatch());

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.kafka.consumer.group")))
                .isEqualTo("order-processors");
    }

    @Test
    void batchHandlerEndsSpanOnException() {
        Handler<KafkaConsumerRecords<String, String>> traced = KafkaTracing.tracedBatchHandler(
                "orders",
                records -> { throw new RuntimeException("processing failed"); },
                otel.getOpenTelemetry()
        );

        assertThatThrownBy(() -> traced.handle(emptyBatch()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("processing failed");

        // Span is still exported and ended even though an exception was thrown
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    // ---- Helpers ----

    private KafkaConsumerRecords<String, String> emptyBatch() {
        return new KafkaConsumerRecordsImpl<>(ConsumerRecords.empty());
    }

    private ConsumerRecords<String, String> recordsWithSize(String topic, int count) {
        TopicPartition partition = new TopicPartition(topic, 0);
        List<ConsumerRecord<String, String>> recordList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            recordList.add(new ConsumerRecord<>(topic, 0, i, "key-" + i, "value-" + i));
        }
        return new ConsumerRecords<>(Collections.singletonMap(partition, recordList));
    }
}
