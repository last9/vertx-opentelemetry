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

class ReactiveSqlHelperTest {

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
        Span span = ReactiveSqlHelper.startSpan("SELECT * FROM users WHERE id = ?", null);

        assertThat(span).isNotNull();
        span.end();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getName()).isEqualTo("SELECT users");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("mysql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("SELECT * FROM users WHERE id = ?");
    }

    @Test
    void startSpanExtractsOperationFromSql() {
        Span span = ReactiveSqlHelper.startSpan("INSERT INTO orders (product) VALUES (?)", null);
        assertThat(span).isNotNull();
        span.end();

        assertThat(spanExporter.getFinishedSpanItems().get(0).getName())
                .isEqualTo("INSERT orders");
    }

    @Test
    void startSpanReturnsNullWhenGuardIsSet() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);

        Span span = ReactiveSqlHelper.startSpan("SELECT 1", null);

        assertThat(span).isNull();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void startSpanHandlesNullSql() {
        Span span = ReactiveSqlHelper.startSpan(null, null);

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("SQL");
    }

    @Test
    void endSpanRecordsError() {
        Span span = ReactiveSqlHelper.startSpan("SELECT 1", null);
        Scope scope = span.makeCurrent();

        ReactiveSqlHelper.endSpan(span, scope, new RuntimeException("pool exhausted"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void endSpanHandlesNullSpan() {
        ReactiveSqlHelper.endSpan(null, null, null);
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    // --- extractDbInfo tests (db.system and db.name detection via reflection) ---

    /**
     * Stub that simulates a MySQL pool client — class name contains "mysql".
     * Has a field with getDatabase() method to simulate SqlConnectOptions.
     */
    @SuppressWarnings("unused")
    static class StubMysqlPoolClient {
        final StubConnectOptions connectOptions = new StubConnectOptions("mydb");
    }

    @SuppressWarnings("unused")
    static class StubPgPoolClient {
        final StubConnectOptions connectOptions = new StubConnectOptions("analytics");
    }

    @SuppressWarnings("unused")
    static class StubConnectOptions {
        private final String database;
        StubConnectOptions(String database) { this.database = database; }
        public String getDatabase() { return database; }
    }

    @Test
    void startSpanDetectsMysqlFromClassName() {
        // Class name doesn't contain "pg" or "postgres", defaults to mysql
        Span span = ReactiveSqlHelper.startSpan("SELECT * FROM users", new StubMysqlPoolClient());
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("mysql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("mydb");
        assertThat(sd.getName()).isEqualTo("SELECT mydb.users");
    }

    @Test
    void startSpanDetectsPostgresqlFromClassName() {
        // StubPgPoolClient contains "pg" in class name
        Span span = ReactiveSqlHelper.startSpan("INSERT INTO events (type) VALUES (?)",
                new StubPgPoolClient());
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("postgresql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("analytics");
    }

    @Test
    void startSpanDefaultsToMysqlWhenNullClient() {
        Span span = ReactiveSqlHelper.startSpan("SELECT 1", null);
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("mysql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isNull();
    }

    @Test
    void endSpanSuccessNoError() {
        Span span = ReactiveSqlHelper.startSpan("SELECT 1", null);
        Scope scope = span.makeCurrent();

        ReactiveSqlHelper.endSpan(span, scope, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).isEmpty();
    }
}
