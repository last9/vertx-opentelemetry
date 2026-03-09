package io.last9.tracing.otel.v3;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.reactivex.sqlclient.Tuple;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracedMySQLClientTest {

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

    // ---- Tests ----

    @Test
    void queryCreatesClientSpan() {
        TracedMySQLClient traced = createTracedClient(null);
        traced.query("SELECT * FROM orders").blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("SELECT test_db.orders");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("mysql");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("SELECT * FROM orders");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("test_db");
    }

    @Test
    void preparedQueryCreatesClientSpan() {
        TracedMySQLClient traced = createTracedClient(null);
        String sql = "SELECT * FROM orders WHERE id = ?";
        traced.preparedQuery(sql, Tuple.of(42)).blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("SELECT test_db.orders");
        assertThat(spans.get(0).getKind()).isEqualTo(SpanKind.CLIENT);
    }

    @Test
    void preparedQueryWithoutArgsCreatesClientSpan() {
        TracedMySQLClient traced = createTracedClient(null);
        String sql = "SELECT COUNT(*) FROM users";
        traced.preparedQuery(sql).blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("SELECT test_db.users");
    }

    @Test
    void errorRecordedOnSpan() {
        RuntimeException error = new RuntimeException("connection refused");
        TracedMySQLClient traced = createTracedClient(error);

        assertThatThrownBy(() -> traced.query("SELECT 1").blockingGet())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("connection refused");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void wrapWithoutDbNameOmitsDbNameAttribute() {
        io.vertx.reactivex.mysqlclient.MySQLPool rxPool = buildRxPool(null);
        TracedMySQLClient traced = TracedMySQLClient.wrap(rxPool, null, otel.getOpenTelemetry());

        traced.query("SELECT 1").blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("db.name"))).isNull();
    }

    @Test
    void unwrapReturnsUnderlyingPool() {
        io.vertx.reactivex.mysqlclient.MySQLPool rxPool = buildRxPool(null);
        TracedMySQLClient traced = TracedMySQLClient.wrap(rxPool, "db", otel.getOpenTelemetry());

        assertThat(traced.unwrap()).isSameAs(rxPool);
    }

    // ---- Stub builders ----

    private TracedMySQLClient createTracedClient(RuntimeException error) {
        io.vertx.reactivex.mysqlclient.MySQLPool rxPool = buildRxPool(error);
        return TracedMySQLClient.wrap(rxPool, "test_db", otel.getOpenTelemetry());
    }

    /**
     * Builds an {@code io.vertx.reactivex.mysqlclient.MySQLPool} backed by a dynamic proxy of
     * the core {@code io.vertx.mysqlclient.MySQLPool} interface.
     *
     * <p>The proxy intercepts {@code query()} and {@code preparedQuery()} calls, returning
     * nested {@link Query}/{@link PreparedQuery} proxies that complete with a stub
     * {@link RowSet} (or fail with {@code error} if provided).
     */
    @SuppressWarnings("unchecked")
    private io.vertx.reactivex.mysqlclient.MySQLPool buildRxPool(RuntimeException error) {
        // Stub RowSet<Row> — just needs to be non-null for RxJava2
        RowSet<Row> fakeRows = (RowSet<Row>) Proxy.newProxyInstance(
                RowSet.class.getClassLoader(),
                new Class<?>[]{RowSet.class},
                (proxy, method, args) -> {
                    if ("iterator".equals(method.getName())) return Collections.emptyIterator();
                    if ("size".equals(method.getName())) return 0;
                    if ("rowCount".equals(method.getName())) return 0;
                    return null;
                });

        // Stub Query<RowSet<Row>> — called by pool.query(sql) delegate
        Query<RowSet<Row>> queryProxy = (Query<RowSet<Row>>) Proxy.newProxyInstance(
                Query.class.getClassLoader(),
                new Class<?>[]{Query.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName()) && args != null && args.length >= 1) {
                        Handler<AsyncResult<RowSet<Row>>> handler =
                                (Handler<AsyncResult<RowSet<Row>>>) args[args.length - 1];
                        if (error != null) {
                            handler.handle(Future.failedFuture(error));
                        } else {
                            handler.handle(Future.succeededFuture(fakeRows));
                        }
                    }
                    return null;
                });

        // Stub PreparedQuery<RowSet<Row>> — called by pool.preparedQuery(sql) delegate
        // PreparedQuery extends Query, so it handles both execute(Handler) and execute(Tuple, Handler)
        PreparedQuery<RowSet<Row>> preparedQueryProxy =
                (PreparedQuery<RowSet<Row>>) Proxy.newProxyInstance(
                        PreparedQuery.class.getClassLoader(),
                        new Class<?>[]{PreparedQuery.class},
                        (proxy, method, args) -> {
                            if ("execute".equals(method.getName()) && args != null && args.length >= 1) {
                                Handler<AsyncResult<RowSet<Row>>> handler =
                                        (Handler<AsyncResult<RowSet<Row>>>) args[args.length - 1];
                                if (error != null) {
                                    handler.handle(Future.failedFuture(error));
                                } else {
                                    handler.handle(Future.succeededFuture(fakeRows));
                                }
                            }
                            return null;
                        });

        // Stub the core io.vertx.mysqlclient.MySQLPool interface
        io.vertx.mysqlclient.MySQLPool corePoolStub =
                (io.vertx.mysqlclient.MySQLPool) Proxy.newProxyInstance(
                        io.vertx.mysqlclient.MySQLPool.class.getClassLoader(),
                        new Class<?>[]{io.vertx.mysqlclient.MySQLPool.class},
                        (proxy, method, args) -> {
                            if ("query".equals(method.getName())) return queryProxy;
                            if ("preparedQuery".equals(method.getName())) return preparedQueryProxy;
                            return null;
                        });

        return io.vertx.reactivex.mysqlclient.MySQLPool.newInstance(corePoolStub);
    }
}
