package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SqsReceiveHelper} — verifies CONSUMER span creation for SQS
 * receiveMessage calls.
 *
 * <p>Uses mock request/response objects that expose the same method signatures
 * as the AWS SDK (via reflection), so no AWS SDK dependency is needed.
 */
class SqsReceiveHelperTest {

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

    // --- Mock objects that mimic AWS SDK method signatures via reflection ---

    /** Mimics SDK v1 ReceiveMessageRequest (has getQueueUrl()) */
    public static class MockSdkV1Request {
        private final String queueUrl;

        MockSdkV1Request(String queueUrl) {
            this.queueUrl = queueUrl;
        }

        public String getQueueUrl() {
            return queueUrl;
        }
    }

    /** Mimics SDK v2 ReceiveMessageRequest (has queueUrl()) */
    public static class MockSdkV2Request {
        private final String queueUrl;

        MockSdkV2Request(String queueUrl) {
            this.queueUrl = queueUrl;
        }

        public String queueUrl() {
            return queueUrl;
        }
    }

    /** Mimics SDK v1 ReceiveMessageResult (has getMessages()) */
    public static class MockSdkV1Response {
        private final List<Object> messages;

        MockSdkV1Response(int messageCount) {
            this.messages = new ArrayList<>(Collections.nCopies(messageCount, new Object()));
        }

        public List<Object> getMessages() {
            return messages;
        }
    }

    /** Mimics SDK v2 ReceiveMessageResponse (has messages()) */
    public static class MockSdkV2Response {
        private final List<Object> messages;

        MockSdkV2Response(int messageCount) {
            this.messages = new ArrayList<>(Collections.nCopies(messageCount, new Object()));
        }

        public List<Object> messages() {
            return messages;
        }
    }

    // --- Tests ---

    @Test
    void startSpanCreatesConsumerSpanWithSdkV1Request() {
        MockSdkV1Request request = new MockSdkV1Request(
                "https://sqs.us-east-1.amazonaws.com/123456789/my-queue");

        Object[] result = SqsReceiveHelper.startSpan(request);

        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
        assertThat(result[0]).isInstanceOf(Span.class);
        assertThat(result[1]).isInstanceOf(Scope.class);

        Span span = (Span) result[0];
        Scope scope = (Scope) result[1];

        // Verify span is current
        assertThat(Span.current()).isSameAs(span);

        // End the span to check attributes
        SqsReceiveHelper.endSpan(span, scope, new MockSdkV1Response(3), null);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getName()).isEqualTo("my-queue receive");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("messaging.system")))
                .isEqualTo("aws_sqs");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("my-queue");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("messaging.operation")))
                .isEqualTo("receive");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("messaging.aws.sqs.queue_url")))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789/my-queue");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("peer.service")))
                .isEqualTo("sqs");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("server.address")))
                .isEqualTo("sqs.us-east-1.amazonaws.com");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("net.peer.name")))
                .isEqualTo("sqs.us-east-1.amazonaws.com");
        assertThat(sd.getAttributes().get(AttributeKey.longKey("messaging.batch.message_count")))
                .isEqualTo(3L);
    }

    @Test
    void startSpanCreatesConsumerSpanWithSdkV2Request() {
        MockSdkV2Request request = new MockSdkV2Request(
                "https://sqs.ap-south-1.amazonaws.com/987654321/notifications-queue");

        Object[] result = SqsReceiveHelper.startSpan(request);
        assertThat(result).isNotNull();

        Span span = (Span) result[0];
        Scope scope = (Scope) result[1];

        SqsReceiveHelper.endSpan(span, scope, new MockSdkV2Response(5), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("notifications-queue receive");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("notifications-queue");
        assertThat(sd.getAttributes().get(AttributeKey.longKey("messaging.batch.message_count")))
                .isEqualTo(5L);
    }

    @Test
    void endSpanRecordsZeroMessagesForEmptyResponse() {
        MockSdkV1Request request = new MockSdkV1Request(
                "https://sqs.us-east-1.amazonaws.com/123456789/empty-queue");

        Object[] result = SqsReceiveHelper.startSpan(request);
        Span span = (Span) result[0];
        Scope scope = (Scope) result[1];

        SqsReceiveHelper.endSpan(span, scope, new MockSdkV1Response(0), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.longKey("messaging.batch.message_count")))
                .isEqualTo(0L);
    }

    @Test
    void endSpanRecordsErrorOnException() {
        MockSdkV1Request request = new MockSdkV1Request(
                "https://sqs.us-east-1.amazonaws.com/123456789/my-queue");

        Object[] result = SqsReceiveHelper.startSpan(request);
        Span span = (Span) result[0];
        Scope scope = (Scope) result[1];

        SqsReceiveHelper.endSpan(span, scope, null,
                new RuntimeException("SQS service unavailable"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getStatus().getDescription()).isEqualTo("SQS service unavailable");
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void endSpanHandlesNullSpan() {
        // Should not throw
        SqsReceiveHelper.endSpan(null, null, null, null);
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void endSpanClosesScope() {
        MockSdkV1Request request = new MockSdkV1Request(
                "https://sqs.us-east-1.amazonaws.com/123456789/my-queue");

        Object[] result = SqsReceiveHelper.startSpan(request);
        Span span = (Span) result[0];
        Scope scope = (Scope) result[1];

        // Span is current before endSpan
        assertThat(Span.current()).isSameAs(span);

        SqsReceiveHelper.endSpan(span, scope, new MockSdkV1Response(1), null);

        // Scope closed — span is no longer current
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void queueNameExtractedFromVariousUrlFormats() {
        // Standard format
        MockSdkV1Request req1 = new MockSdkV1Request(
                "https://sqs.us-east-1.amazonaws.com/123456789/order-events");
        Object[] r1 = SqsReceiveHelper.startSpan(req1);
        SqsReceiveHelper.endSpan((Span) r1[0], (Scope) r1[1], new MockSdkV1Response(0), null);

        // FIFO queue
        MockSdkV1Request req2 = new MockSdkV1Request(
                "https://sqs.eu-west-1.amazonaws.com/999999999/payments.fifo");
        Object[] r2 = SqsReceiveHelper.startSpan(req2);
        SqsReceiveHelper.endSpan((Span) r2[0], (Scope) r2[1], new MockSdkV1Response(0), null);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).getName()).isEqualTo("order-events receive");
        assertThat(spans.get(1).getName()).isEqualTo("payments.fifo receive");
    }

    @Test
    void downstreamSpansAreChildrenOfSqsConsumerSpan() {
        MockSdkV1Request request = new MockSdkV1Request(
                "https://sqs.us-east-1.amazonaws.com/123456789/my-queue");

        Object[] result = SqsReceiveHelper.startSpan(request);
        Span sqsSpan = (Span) result[0];
        Scope sqsScope = (Scope) result[1];

        // Simulate a downstream call while the SQS span is current
        Span childSpan = otel.getOpenTelemetry().getTracer("test")
                .spanBuilder("redis GET")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        childSpan.end();

        SqsReceiveHelper.endSpan(sqsSpan, sqsScope, new MockSdkV1Response(1), null);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData redisSpan = spans.stream()
                .filter(s -> s.getName().equals("redis GET"))
                .findFirst().orElseThrow();
        SpanData sqsSpanData = spans.stream()
                .filter(s -> s.getName().equals("my-queue receive"))
                .findFirst().orElseThrow();

        // The Redis span's parent should be the SQS consumer span
        assertThat(redisSpan.getParentSpanId()).isEqualTo(sqsSpanData.getSpanId());
    }

    @Test
    void sqsSpanIsRootEvenWhenParentSpanIsActive() {
        // Simulate an active HTTP server span (e.g., from a Vert.x timer context)
        Span httpSpan = otel.getOpenTelemetry().getTracer("test")
                .spanBuilder("GET /health")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        Scope httpScope = httpSpan.makeCurrent();

        try {
            MockSdkV1Request request = new MockSdkV1Request(
                    "https://sqs.us-east-1.amazonaws.com/123456789/my-queue");
            Object[] result = SqsReceiveHelper.startSpan(request);
            SqsReceiveHelper.endSpan((Span) result[0], (Scope) result[1],
                    new MockSdkV1Response(2), null);
        } finally {
            httpScope.close();
            httpSpan.end();
        }

        SpanData sqsSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("my-queue receive"))
                .findFirst().orElseThrow();

        // SQS consumer span must be a root span — NOT a child of the HTTP span
        assertThat(sqsSpan.getParentSpanId()).isEqualTo("0000000000000000");
    }

    @Test
    void multipleReceiveCallsProduceSeparateSpans() {
        for (int i = 0; i < 3; i++) {
            MockSdkV1Request request = new MockSdkV1Request(
                    "https://sqs.us-east-1.amazonaws.com/123456789/batch-queue");
            Object[] result = SqsReceiveHelper.startSpan(request);
            SqsReceiveHelper.endSpan((Span) result[0], (Scope) result[1],
                    new MockSdkV1Response(i + 1), null);
        }

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(3);

        // Each span has a different message count
        assertThat(spans.get(0).getAttributes().get(AttributeKey.longKey("messaging.batch.message_count")))
                .isEqualTo(1L);
        assertThat(spans.get(1).getAttributes().get(AttributeKey.longKey("messaging.batch.message_count")))
                .isEqualTo(2L);
        assertThat(spans.get(2).getAttributes().get(AttributeKey.longKey("messaging.batch.message_count")))
                .isEqualTo(3L);
    }
}
