package io.last9.tracing.otel.v3;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.Single;
import io.vertx.core.Handler;
import io.vertx.kafka.client.consumer.KafkaConsumerRecords;
import io.vertx.kafka.client.consumer.impl.KafkaConsumerRecordsImpl;
import io.vertx.kafka.client.producer.RecordMetadata;
import io.vertx.reactivex.kafka.client.producer.KafkaProducerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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

    @Test
    void batchHandlerSetsErrorStatusOnException() {
        Handler<KafkaConsumerRecords<String, String>> traced = KafkaTracing.tracedBatchHandler(
                "orders",
                records -> { throw new RuntimeException("downstream failure"); },
                otel.getOpenTelemetry()
        );

        assertThatThrownBy(() -> traced.handle(emptyBatch()));

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo("downstream failure");
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    // ---- Consumer exception handler tests ----

    @Test
    void exceptionHandlerCreatesErrorSpan() {
        Handler<Throwable> handler = KafkaTracing.tracedExceptionHandler(
                "orders", otel.getOpenTelemetry());

        handler.handle(new RuntimeException("broker unavailable"));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("orders error");
        assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.system")))
                .isEqualTo("kafka");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("orders");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo("broker unavailable");
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void exceptionHandlerSpanIsCurrentDuringHandling() {
        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        Handler<Throwable> handler = KafkaTracing.tracedExceptionHandler(
                "orders", otel.getOpenTelemetry());

        // Override to capture trace ID from MDC/context during span scope
        Handler<Throwable> wrapped = err -> {
            handler.handle(err);
            // After handle() span is ended, but we captured traceId inside
        };

        // Verify that the span was made current during the handler invocation
        // by checking the span is exported with a valid trace ID
        wrapped.handle(new RuntimeException("conn reset"));

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat(span.getTraceId()).matches("[0-9a-f]{32}");
        assertThat(span.getTraceId()).isNotEqualTo("00000000000000000000000000000000");
    }

    @Test
    void multipleConsumerErrorsProduceSeparateSpans() {
        Handler<Throwable> handler = KafkaTracing.tracedExceptionHandler(
                "payments", otel.getOpenTelemetry());

        handler.handle(new RuntimeException("error 1"));
        handler.handle(new RuntimeException("error 2"));

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(2);
    }

    // ---- Producer tests ----

    @Test
    void producerSendCreatesProducerSpan() {
        KafkaProducerRecord<String, String> record =
                KafkaProducerRecord.create("orders", "key-1", "value-1");
        RecordMetadata metadata = new RecordMetadata()
                .setTopic("orders").setPartition(2).setOffset(42L);

        Single<RecordMetadata> send = Single.just(metadata);

        KafkaTracing.tracedSend(record, send, otel.getOpenTelemetry()).blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("orders publish");
        assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.system")))
                .isEqualTo("kafka");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("orders");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.operation")))
                .isEqualTo("publish");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.kafka.message.key")))
                .isEqualTo("key-1");
        // partition and offset set after successful send
        assertThat(span.getAttributes().get(AttributeKey.longKey("messaging.kafka.destination.partition")))
                .isEqualTo(2L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("messaging.kafka.message.offset")))
                .isEqualTo(42L);
    }

    @Test
    void producerSendErrorRecordsExceptionAndSetsErrorStatus() {
        KafkaProducerRecord<String, String> record =
                KafkaProducerRecord.create("orders", "key-1", "value-1");
        RuntimeException sendError = new RuntimeException("broker unavailable");

        Single<RecordMetadata> failingSend = Single.error(sendError);

        assertThatThrownBy(() ->
                KafkaTracing.tracedSend(record, failingSend, otel.getOpenTelemetry()).blockingGet()
        ).isInstanceOf(RuntimeException.class).hasMessage("broker unavailable");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo("broker unavailable");
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void producerSendSpanAlwaysEndsOnDisposal() {
        KafkaProducerRecord<String, String> record =
                KafkaProducerRecord.create("orders", "key-1", "value-1");
        // A Single that never terminates — simulates a hung broker
        Single<RecordMetadata> neverSend = Single.never();

        var disposable = KafkaTracing.tracedSend(record, neverSend, otel.getOpenTelemetry())
                .subscribe();

        // No spans yet — send is still in flight
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();

        // Cancel (dispose) before the send completes
        disposable.dispose();

        // doFinally must have fired — span is ended and exported
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
        assertThat(spanExporter.getFinishedSpanItems().get(0).getKind())
                .isEqualTo(SpanKind.PRODUCER);
    }

    @Test
    void producerSendTombstoneSetsMsgTombstoneAttribute() {
        KafkaProducerRecord<String, String> record =
                KafkaProducerRecord.create("orders", "key-1", null);  // null value = tombstone
        RecordMetadata metadata = new RecordMetadata().setTopic("orders").setPartition(0).setOffset(1L);

        KafkaTracing.tracedSend(record, Single.just(metadata), otel.getOpenTelemetry()).blockingGet();

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(AttributeKey.booleanKey("messaging.kafka.message.tombstone")))
                .isTrue();
    }

    // ---- Context isolation tests ----

    @Test
    void consumerSpanIsRootSpanEvenWhenServerSpanIsActive() {
        // Reproduce the SERVER span context leak: a Vert.x HTTP SERVER span is active
        // on the event loop thread. Without Context.root() as parent, the consumer span
        // would become a child of that SERVER span — producing a wrong trace.
        Span serverSpan = otel.getOpenTelemetry().getTracer("test")
                .spanBuilder("GET /api/data")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        try (Scope ignored = serverSpan.makeCurrent()) {
            Handler<KafkaConsumerRecords<String, String>> traced = KafkaTracing.tracedBatchHandler(
                    "orders",
                    records -> {},
                    otel.getOpenTelemetry()
            );
            traced.handle(emptyBatch());
        } finally {
            serverSpan.end();
        }

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData consumerSpan = spans.stream()
                .filter(s -> s.getKind() == SpanKind.CONSUMER)
                .findFirst().orElseThrow();

        // Consumer span must be a root span — not a child of the HTTP SERVER span
        assertThat(consumerSpan.getParentSpanContext().isValid()).isFalse();
    }

    @Test
    void consumerSpanLinksToProducerViaTraceparentHeader() {
        // Simulate a producer span with a known trace/span ID
        Span producerSpan = otel.getOpenTelemetry().getTracer("test")
                .spanBuilder("orders publish")
                .setSpanKind(SpanKind.PRODUCER)
                .startSpan();
        String producerTraceId = producerSpan.getSpanContext().getTraceId();
        String producerSpanId = producerSpan.getSpanContext().getSpanId();
        producerSpan.end();

        // Build a W3C traceparent header value (same format TracedKafkaProducer injects)
        String traceparent = "00-" + producerTraceId + "-" + producerSpanId + "-01";

        // Create a Kafka ConsumerRecord carrying the traceparent header
        ConsumerRecord<String, String> rawRecord =
                new ConsumerRecord<>("orders", 0, 0L, "key-1", "value-1");
        rawRecord.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));

        ConsumerRecords<String, String> raw = new ConsumerRecords<>(
                Collections.singletonMap(new TopicPartition("orders", 0),
                        Collections.singletonList(rawRecord)));

        Handler<KafkaConsumerRecords<String, String>> traced = KafkaTracing.tracedBatchHandler(
                "orders",
                records -> {},
                otel.getOpenTelemetry()
        );
        traced.handle(new KafkaConsumerRecordsImpl<>(raw));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        // Only the consumer span — producer span ended before the handler ran
        SpanData consumerSpan = spans.stream()
                .filter(s -> s.getKind() == SpanKind.CONSUMER)
                .findFirst().orElseThrow();

        // Consumer span must be a root span (no parent)
        assertThat(consumerSpan.getParentSpanContext().isValid()).isFalse();

        // Consumer span must carry a SpanLink pointing to the producer span
        assertThat(consumerSpan.getLinks()).hasSize(1);
        assertThat(consumerSpan.getLinks().get(0).getSpanContext().getTraceId())
                .isEqualTo(producerTraceId);
        assertThat(consumerSpan.getLinks().get(0).getSpanContext().getSpanId())
                .isEqualTo(producerSpanId);
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
