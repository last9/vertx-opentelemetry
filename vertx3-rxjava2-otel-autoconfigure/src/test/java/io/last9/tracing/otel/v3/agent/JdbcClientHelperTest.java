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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcClientHelperTest {

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
    void startSpanCreatesClientSpanForSelectQuery() {
        Span span = JdbcClientHelper.startSpan("SELECT * FROM orders WHERE id = ?");

        assertThat(span).isNotNull();
        span.end();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getName()).isEqualTo("SELECT orders");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("other_sql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("SELECT * FROM orders WHERE id = ?");
    }

    @Test
    void startSpanCreatesSpanForInsert() {
        Span span = JdbcClientHelper.startSpan("INSERT INTO users (name) VALUES (?)");

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("INSERT users");
    }

    @Test
    void startSpanCreatesSpanForUpdate() {
        Span span = JdbcClientHelper.startSpan("UPDATE orders SET status = 'shipped'");

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("UPDATE orders");
    }

    @Test
    void startSpanCreatesSpanForDelete() {
        Span span = JdbcClientHelper.startSpan("DELETE FROM sessions WHERE expired = true");

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("DELETE sessions");
    }

    @Test
    void startSpanReturnsNullWhenGuardIsSet() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);

        Span span = JdbcClientHelper.startSpan("SELECT 1");

        assertThat(span).isNull();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void startSpanHandlesNullSql() {
        Span span = JdbcClientHelper.startSpan(null);

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("SQL");
    }

    @Test
    void endSpanRecordsError() {
        Span span = JdbcClientHelper.startSpan("SELECT 1");
        Scope scope = span.makeCurrent();

        JdbcClientHelper.endSpan(span, scope, new RuntimeException("connection refused"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void endSpanHandlesNullSpan() {
        JdbcClientHelper.endSpan(null, null, null);
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }
}
