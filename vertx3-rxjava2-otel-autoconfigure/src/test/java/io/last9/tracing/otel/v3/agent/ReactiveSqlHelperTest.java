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
        ReactiveSqlHelper.POOL_METADATA.clear();
    }

    @AfterEach
    void tearDown() {
        AgentGuard.IN_DB_TRACED_CALL.remove();
        ReactiveSqlHelper.POOL_METADATA.clear();
        otel.tearDown();
    }

    // --- Stubs ---

    /** Stub connect options with getHost(), getPort(), getDatabase(). */
    @SuppressWarnings("unused")
    static class StubConnectOptions {
        private final String host;
        private final int port;
        private final String database;

        StubConnectOptions(String host, int port, String database) {
            this.host = host;
            this.port = port;
            this.database = database;
        }

        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getDatabase() { return database; }
    }

    /** Stub MySQL pool — class name contains "mysql". */
    @SuppressWarnings("unused")
    static class StubMysqlPoolClient {}

    /** Stub PG pool — class name contains "Pg". */
    @SuppressWarnings("unused")
    static class StubPgPoolClient {}

    /** Stub with no relevant methods — tests graceful fallback. */
    @SuppressWarnings("unused")
    static class StubBareConnectOptions {}

    // --- registerPool tests ---

    @Test
    void registerPoolStoresMetadataForMysqlPool() {
        StubMysqlPoolClient pool = new StubMysqlPoolClient();
        StubConnectOptions opts = new StubConnectOptions("mysql.internal", 3306, "live_streaming");

        ReactiveSqlHelper.registerPool(pool, opts);

        ReactiveSqlHelper.DbConnectionInfo info =
                ReactiveSqlHelper.POOL_METADATA.get(System.identityHashCode(pool));
        assertThat(info).isNotNull();
        assertThat(info.dbSystem).isEqualTo("mysql");
        assertThat(info.dbName).isEqualTo("live_streaming");
        assertThat(info.host).isEqualTo("mysql.internal");
        assertThat(info.port).isEqualTo(3306);
    }

    @Test
    void registerPoolStoresMetadataForPgPool() {
        StubPgPoolClient pool = new StubPgPoolClient();
        StubConnectOptions opts = new StubConnectOptions("pg.internal", 5432, "analytics");

        ReactiveSqlHelper.registerPool(pool, opts);

        ReactiveSqlHelper.DbConnectionInfo info =
                ReactiveSqlHelper.POOL_METADATA.get(System.identityHashCode(pool));
        assertThat(info).isNotNull();
        assertThat(info.dbSystem).isEqualTo("postgresql");
        assertThat(info.dbName).isEqualTo("analytics");
        assertThat(info.host).isEqualTo("pg.internal");
        assertThat(info.port).isEqualTo(5432);
    }

    @Test
    void registerPoolHandlesNullPool() {
        ReactiveSqlHelper.registerPool(null, new StubConnectOptions("h", 1, "d"));
        assertThat(ReactiveSqlHelper.POOL_METADATA).isEmpty();
    }

    @Test
    void registerPoolHandlesNullOptions() {
        ReactiveSqlHelper.registerPool(new Object(), null);
        assertThat(ReactiveSqlHelper.POOL_METADATA).isEmpty();
    }

    @Test
    void registerPoolHandlesBareOptionsGracefully() {
        StubMysqlPoolClient pool = new StubMysqlPoolClient();
        ReactiveSqlHelper.registerPool(pool, new StubBareConnectOptions());

        ReactiveSqlHelper.DbConnectionInfo info =
                ReactiveSqlHelper.POOL_METADATA.get(System.identityHashCode(pool));
        assertThat(info).isNotNull();
        assertThat(info.dbSystem).isEqualTo("mysql");
        assertThat(info.dbName).isNull();
        assertThat(info.host).isNull();
        assertThat(info.port).isEqualTo(-1);
    }

    // --- startSpan with registered pool (DD-style path) ---

    @Test
    void startSpanUsesRegisteredMetadataForDbNameAndHost() {
        StubMysqlPoolClient pool = new StubMysqlPoolClient();
        ReactiveSqlHelper.registerPool(pool,
                new StubConnectOptions("mysql-rds.internal", 3306, "live_streaming"));

        Span span = ReactiveSqlHelper.startSpan(
                "SELECT streamId FROM DreamBucks WHERE streamId IN (?)", pool);
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("SELECT live_streaming.DreamBucks");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("mysql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("live_streaming");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("SELECT streamId FROM DreamBucks WHERE streamId IN (?)");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("net.peer.name")))
                .isEqualTo("mysql-rds.internal");
        assertThat(sd.getAttributes().get(AttributeKey.longKey("net.peer.port")))
                .isEqualTo(3306L);
    }

    @Test
    void startSpanUsesRegisteredMetadataForPostgresql() {
        StubPgPoolClient pool = new StubPgPoolClient();
        ReactiveSqlHelper.registerPool(pool,
                new StubConnectOptions("pg-primary.internal", 5432, "events_db"));

        Span span = ReactiveSqlHelper.startSpan("INSERT INTO events (type) VALUES (?)", pool);
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("postgresql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("events_db");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("net.peer.name")))
                .isEqualTo("pg-primary.internal");
        assertThat(sd.getAttributes().get(AttributeKey.longKey("net.peer.port")))
                .isEqualTo(5432L);
    }

    @Test
    void startSpanOmitsHostWhenNotInMetadata() {
        StubMysqlPoolClient pool = new StubMysqlPoolClient();
        ReactiveSqlHelper.registerPool(pool, new StubBareConnectOptions());

        Span span = ReactiveSqlHelper.startSpan("SELECT 1", pool);
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("mysql");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("net.peer.name")))
                .isNull();
        assertThat(sd.getAttributes().get(AttributeKey.longKey("net.peer.port")))
                .isNull();
    }

    // --- startSpan fallback (unregistered pool) ---

    @Test
    void startSpanFallsBackToClassNameDetectionWhenPoolNotRegistered() {
        // Pool not registered — should still detect db.system from class name
        Span span = ReactiveSqlHelper.startSpan("SELECT * FROM users", new StubPgPoolClient());
        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("postgresql");
        // No db.name or host when pool is not registered
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isNull();
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("net.peer.name")))
                .isNull();
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

    // --- Guard and null handling ---

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
    void startSpanExtractsOperationFromSql() {
        Span span = ReactiveSqlHelper.startSpan("INSERT INTO orders (product) VALUES (?)", null);
        assertThat(span).isNotNull();
        span.end();

        assertThat(spanExporter.getFinishedSpanItems().get(0).getName())
                .isEqualTo("INSERT orders");
    }

    // --- endSpan tests ---

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
    void endSpanSuccessNoError() {
        Span span = ReactiveSqlHelper.startSpan("SELECT 1", null);
        Scope scope = span.makeCurrent();

        ReactiveSqlHelper.endSpan(span, scope, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).isEmpty();
    }

    @Test
    void endSpanHandlesNullSpan() {
        ReactiveSqlHelper.endSpan(null, null, null);
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    // --- Multiple pools ---

    @Test
    void multiplePoolsTrackedIndependently() {
        StubMysqlPoolClient mysqlPool = new StubMysqlPoolClient();
        StubPgPoolClient pgPool = new StubPgPoolClient();

        ReactiveSqlHelper.registerPool(mysqlPool,
                new StubConnectOptions("mysql.host", 3306, "app_db"));
        ReactiveSqlHelper.registerPool(pgPool,
                new StubConnectOptions("pg.host", 5432, "analytics"));

        Span s1 = ReactiveSqlHelper.startSpan("SELECT * FROM users", mysqlPool);
        s1.end();
        Span s2 = ReactiveSqlHelper.startSpan("SELECT * FROM events", pgPool);
        s2.end();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData mysql = spans.get(0);
        assertThat(mysql.getAttributes().get(AttributeKey.stringKey("db.system"))).isEqualTo("mysql");
        assertThat(mysql.getAttributes().get(AttributeKey.stringKey("db.name"))).isEqualTo("app_db");
        assertThat(mysql.getAttributes().get(AttributeKey.stringKey("net.peer.name"))).isEqualTo("mysql.host");

        SpanData pg = spans.get(1);
        assertThat(pg.getAttributes().get(AttributeKey.stringKey("db.system"))).isEqualTo("postgresql");
        assertThat(pg.getAttributes().get(AttributeKey.stringKey("db.name"))).isEqualTo("analytics");
        assertThat(pg.getAttributes().get(AttributeKey.stringKey("net.peer.name"))).isEqualTo("pg.host");
    }

    @Test
    void registerPoolOverwritesPreviousMetadataForSamePool() {
        StubMysqlPoolClient pool = new StubMysqlPoolClient();

        ReactiveSqlHelper.registerPool(pool,
                new StubConnectOptions("old.host", 3306, "old_db"));
        ReactiveSqlHelper.registerPool(pool,
                new StubConnectOptions("new.host", 3307, "new_db"));

        ReactiveSqlHelper.DbConnectionInfo info =
                ReactiveSqlHelper.POOL_METADATA.get(System.identityHashCode(pool));
        assertThat(info.host).isEqualTo("new.host");
        assertThat(info.port).isEqualTo(3307);
        assertThat(info.dbName).isEqualTo("new_db");
    }
}
