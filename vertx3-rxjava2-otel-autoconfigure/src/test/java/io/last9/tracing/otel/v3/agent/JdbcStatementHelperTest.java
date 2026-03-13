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

class JdbcStatementHelperTest {

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

    // --- Stubs that simulate JDBC reflection chain ---

    @SuppressWarnings("unused")
    static class StubDatabaseMetaData {
        private final String url;
        StubDatabaseMetaData(String url) { this.url = url; }
        public String getURL() { return url; }
    }

    @SuppressWarnings("unused")
    static class StubConnection {
        private final StubDatabaseMetaData metaData;
        StubConnection(String jdbcUrl) { this.metaData = new StubDatabaseMetaData(jdbcUrl); }
        public StubDatabaseMetaData getMetaData() { return metaData; }
    }

    @SuppressWarnings("unused")
    static class StubStatement {
        private final StubConnection connection;
        StubStatement(String jdbcUrl) { this.connection = new StubConnection(jdbcUrl); }
        public StubConnection getConnection() { return connection; }
    }

    /** Statement stub with no connection (simulates getConnection() failure). */
    @SuppressWarnings("unused")
    static class StubStatementNoConnection {
        public Object getConnection() { throw new RuntimeException("no connection"); }
    }

    // --- startSpan tests ---

    @Test
    void startSpanCreatesClientSpanForMysqlSelect() {
        StubStatement stmt = new StubStatement("jdbc:mysql://db-host:3306/mydb");

        Span span = JdbcStatementHelper.startSpan("SELECT * FROM users", stmt);
        assertThat(span).isNotNull();
        span.end();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getName()).isEqualTo("SELECT mydb.users");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("mysql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("mydb");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("SELECT * FROM users");
    }

    @Test
    void startSpanDetectsPostgresqlFromJdbcUrl() {
        StubStatement stmt = new StubStatement("jdbc:postgresql://pg-host:5432/analytics");

        Span span = JdbcStatementHelper.startSpan("INSERT INTO events (type) VALUES (?)", stmt);
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("postgresql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("analytics");
        assertThat(sd.getName()).isEqualTo("INSERT analytics.events");
    }

    @Test
    void startSpanFallsBackToOtherSqlWhenNoConnection() {
        StubStatementNoConnection stmt = new StubStatementNoConnection();

        Span span = JdbcStatementHelper.startSpan("SELECT 1", stmt);
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("other_sql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isNull();
    }

    @Test
    void startSpanReturnsNullWhenGuardIsSet() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);

        StubStatement stmt = new StubStatement("jdbc:mysql://host/db");
        Span span = JdbcStatementHelper.startSpan("SELECT 1", stmt);

        assertThat(span).isNull();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void startSpanHandlesNullSql() {
        StubStatement stmt = new StubStatement("jdbc:mysql://host:3306/testdb");

        Span span = JdbcStatementHelper.startSpan(null, stmt);
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("SQL");
    }

    @Test
    void startSpanHandlesUpdateStatement() {
        StubStatement stmt = new StubStatement("jdbc:mysql://host:3306/shop");

        Span span = JdbcStatementHelper.startSpan("UPDATE products SET price = 10 WHERE id = 1", stmt);
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("UPDATE shop.products");
    }

    @Test
    void startSpanHandlesDeleteStatement() {
        StubStatement stmt = new StubStatement("jdbc:postgresql://host:5432/logs");

        Span span = JdbcStatementHelper.startSpan("DELETE FROM old_entries WHERE created_at < ?", stmt);
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("DELETE logs.old_entries");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("postgresql");
    }

    // --- endSpan tests ---

    @Test
    void endSpanRecordsError() {
        StubStatement stmt = new StubStatement("jdbc:mysql://host:3306/db");
        Span span = JdbcStatementHelper.startSpan("SELECT 1", stmt);
        Scope scope = span.makeCurrent();

        JdbcStatementHelper.endSpan(span, scope, new RuntimeException("table not found"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void endSpanHandlesNullSpan() {
        JdbcStatementHelper.endSpan(null, null, null);
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void endSpanSuccessNoError() {
        StubStatement stmt = new StubStatement("jdbc:mysql://host:3306/db");
        Span span = JdbcStatementHelper.startSpan("SELECT 1", stmt);
        Scope scope = span.makeCurrent();

        JdbcStatementHelper.endSpan(span, scope, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).isEmpty();
    }
}
