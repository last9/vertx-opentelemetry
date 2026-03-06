package io.last9.tracing.otel.v3.agent;

import com.aerospike.client.Key;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AerospikeClientHelperTest {

    private GlobalOtelTestSetup otel;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void setUp() {
        otel = new GlobalOtelTestSetup();
        otel.setUp();
        spanExporter = otel.getSpanExporter();
        AgentGuard.IN_DB_TRACED_CALL.set(false);
    }

    @AfterEach
    void tearDown() {
        AgentGuard.IN_DB_TRACED_CALL.remove();
        otel.tearDown();
    }

    @Test
    void startSpanCreatesClientSpanWithKeyAttributes() {
        Key key = new Key("test-ns", "users", "user:123");
        Span span = AerospikeClientHelper.startSpan("GET", key);

        assertThat(span).isNotNull();
        span.end();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getName()).isEqualTo("aerospike GET test-ns.users");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("aerospike");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("test-ns");
    }

    @Test
    void startSpanReturnsNullWhenGuardIsSet() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);

        Key key = new Key("test-ns", "users", "user:123");
        Span span = AerospikeClientHelper.startSpan("GET", key);

        assertThat(span).isNull();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void startSpanHandlesNullKey() {
        Span span = AerospikeClientHelper.startSpan("BATCH_GET", null);

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("aerospike BATCH_GET");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name"))).isNull();
    }

    @Test
    void startBatchSpanCreatesSpanWithKeyCount() {
        Key[] keys = {
                new Key("test-ns", "users", "u1"),
                new Key("test-ns", "users", "u2"),
                new Key("test-ns", "users", "u3")
        };
        Span span = AerospikeClientHelper.startBatchSpan("GET", keys);

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("aerospike GET (3 keys)");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("test-ns");
    }

    @Test
    void startBatchSpanReturnsNullWhenGuardIsSet() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);

        Key[] keys = {new Key("test-ns", "users", "u1")};
        Span span = AerospikeClientHelper.startBatchSpan("GET", keys);

        assertThat(span).isNull();
    }

    @Test
    void endSpanRecordsError() {
        Key key = new Key("test-ns", "users", "user:123");
        Span span = AerospikeClientHelper.startSpan("PUT", key);
        Scope scope = span.makeCurrent();

        AerospikeClientHelper.endSpan(span, scope, new RuntimeException("timeout"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void endSpanHandlesNullSpan() {
        // Should not throw
        AerospikeClientHelper.endSpan(null, null, null);
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void endSpanSuccessNoError() {
        Key key = new Key("test-ns", "cache", "entry:1");
        Span span = AerospikeClientHelper.startSpan("DELETE", key);
        Scope scope = span.makeCurrent();

        AerospikeClientHelper.endSpan(span, scope, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).isEmpty();
    }
}
