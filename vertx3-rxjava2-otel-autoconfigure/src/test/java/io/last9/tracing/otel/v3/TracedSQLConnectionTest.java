package io.last9.tracing.otel.v3;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.UpdateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TracedSQLConnection} — exercised via
 * {@link TracedSQLClient#rxGetConnection()}, which is the only way to obtain an
 * instance (the class is package-private).
 *
 * <p>Covers every operation category: query, update, call, execute, batch,
 * transaction, and lifecycle methods that must NOT produce spans.
 */
class TracedSQLConnectionTest {

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

    // ---- Query ----

    @Test
    void rxQueryCreatesClientSpan() {
        conn(rs(), null, null).rxQuery("SELECT * FROM orders").blockingGet();

        SpanData span = single();
        assertThat(span.getName()).isEqualTo("mysql SELECT * FROM orders");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
    }

    @Test
    void rxQueryWithParamsCreatesSpan() {
        conn(rs(), null, null)
                .rxQueryWithParams("SELECT * FROM orders WHERE id = ?", new JsonArray().add(1))
                .blockingGet();

        assertThat(single().getName()).contains("SELECT * FROM orders WHERE id = ?");
    }

    @Test
    void rxQuerySingleCreatesSpan() {
        // querySingle returns Maybe — may complete empty; span is still produced
        conn(rs(), null, null).rxQuerySingle("SELECT 1").blockingGet();

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void rxQuerySingleWithParamsCreatesSpan() {
        conn(rs(), null, null)
                .rxQuerySingleWithParams("SELECT name FROM users WHERE id = ?",
                        new JsonArray().add(42))
                .blockingGet();

        assertThat(single().getName()).contains("SELECT name FROM users WHERE id = ?");
    }

    @Test
    void rxQueryStreamCreatesSpan() {
        // rxQueryStream returns Single<SQLRowStream> — stub returns null (valid in test)
        conn(null, null, null).rxQueryStream("SELECT id FROM large_table").blockingGet();

        assertThat(single().getName()).contains("SELECT id FROM large_table");
    }

    // ---- Update ----

    @Test
    void rxUpdateCreatesSpan() {
        conn(null, new UpdateResult(), null)
                .rxUpdate("DELETE FROM sessions WHERE expired = true")
                .blockingGet();

        assertThat(single().getName()).contains("DELETE FROM sessions");
    }

    @Test
    void rxUpdateWithParamsCreatesSpan() {
        conn(null, new UpdateResult(), null)
                .rxUpdateWithParams("UPDATE users SET active = ? WHERE id = ?",
                        new JsonArray().add(false).add(7))
                .blockingGet();

        assertThat(single().getName()).contains("UPDATE users SET active");
    }

    // ---- Call (stored procedures) ----

    @Test
    void rxCallCreatesSpan() {
        conn(rs(), null, null).rxCall("{CALL get_orders()}").blockingGet();

        assertThat(single().getName()).contains("CALL get_orders");
    }

    @Test
    void rxCallWithParamsCreatesSpan() {
        conn(rs(), null, null)
                .rxCallWithParams("{CALL update_status(?)}", new JsonArray().add("active"),
                        new JsonArray())
                .blockingGet();

        assertThat(single().getName()).contains("CALL update_status");
    }

    // ---- Execute ----

    @Test
    void rxExecuteCreatesSpan() {
        conn(null, null, null).rxExecute("CREATE INDEX idx_user_id ON orders(user_id)")
                .blockingAwait();

        assertThat(single().getName()).contains("CREATE INDEX");
    }

    // ---- Batch ----

    @Test
    void rxBatchIncludesStatementCountInSpanName() {
        List<String> stmts = Arrays.asList(
                "INSERT INTO log VALUES ('a')",
                "INSERT INTO log VALUES ('b')",
                "INSERT INTO log VALUES ('c')");

        conn(null, null, null).rxBatch(stmts).blockingGet();

        assertThat(single().getName()).isEqualTo("mysql BATCH (3 statements)");
    }

    @Test
    void rxBatchWithParamsIncludesBatchSizeInSpanName() {
        List<JsonArray> params = Arrays.asList(
                new JsonArray().add(1), new JsonArray().add(2));

        conn(null, null, null)
                .rxBatchWithParams("INSERT INTO events (id) VALUES (?)", params)
                .blockingGet();

        assertThat(single().getName()).isEqualTo("mysql INSERT INTO events (id) VALUES (?) (batch 2)");
    }

    @Test
    void rxBatchCallableWithParamsIncludesBatchSizeInSpanName() {
        List<JsonArray> inArgs = Collections.singletonList(new JsonArray().add("x"));
        List<JsonArray> outArgs = Collections.singletonList(new JsonArray());

        conn(null, null, null)
                .rxBatchCallableWithParams("{CALL proc(?)}", inArgs, outArgs)
                .blockingGet();

        assertThat(single().getName()).isEqualTo("mysql {CALL proc(?)} (batch callable 1)");
    }

    // ---- Transaction ----

    @Test
    void rxRollbackCreatesSpan() {
        conn(null, null, null).rxRollback().blockingAwait();

        SpanData span = single();
        assertThat(span.getName()).isEqualTo("mysql ROLLBACK");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
    }

    // ---- Lifecycle: must NOT produce spans ----

    @Test
    void rxSetAutoCommitDoesNotCreateSpan() {
        conn(null, null, null).rxSetAutoCommit(true).blockingAwait();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void rxCloseDoesNotCreateSpan() {
        conn(null, null, null).rxClose().blockingAwait();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    // ---- Error handling ----

    @Test
    void errorOnQueryRecordsExceptionEventAndSetsErrorStatus() {
        RuntimeException err = new RuntimeException("deadlock detected");

        assertThatThrownBy(() ->
                conn(null, null, err).rxQuery("SELECT 1").blockingGet()
        ).isInstanceOf(RuntimeException.class);

        SpanData span = single();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    // ---- Helpers ----

    /** Returns a {@link TracedSQLConnection} obtained via {@link TracedSQLClient#rxGetConnection()}. */
    private io.vertx.reactivex.ext.sql.SQLConnection conn(ResultSet rs, UpdateResult ur,
                                                           RuntimeException error) {
        SQLConnection stubConn = stubConnection(rs, ur, error);
        SQLClient stubClient = stubClient(stubConn);
        io.vertx.reactivex.ext.sql.SQLClient traced = TracedSQLClient.wrap(
                io.vertx.reactivex.ext.sql.SQLClient.newInstance(stubClient),
                "mysql", "orders_db", otel.getOpenTelemetry());
        return traced.rxGetConnection().blockingGet();
    }

    private ResultSet rs() {
        ResultSet rs = new ResultSet();
        rs.setColumnNames(Collections.singletonList("id"));
        rs.setResults(Collections.singletonList(new JsonArray().add(1)));
        return rs;
    }

    private SpanData single() {
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).as("expected exactly one span").hasSize(1);
        return spans.get(0);
    }

    @SuppressWarnings("unchecked")
    private static SQLConnection stubConnection(ResultSet rs, UpdateResult ur,
                                                RuntimeException error) {
        return (SQLConnection) Proxy.newProxyInstance(
                SQLConnection.class.getClassLoader(),
                new Class<?>[]{SQLConnection.class},
                (proxy, method, args) -> {
                    String name = method.getName();

                    if ("close".equals(name)) {
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof Handler) {
                                    ((Handler<AsyncResult<Void>>) arg).handle(
                                            Future.succeededFuture());
                                }
                            }
                        }
                        return null;
                    }

                    if (args != null) {
                        Object last = args[args.length - 1];
                        if (last instanceof Handler) {
                            Handler handler = (Handler) last;
                            if (error != null) {
                                handler.handle(Future.failedFuture(error));
                            } else if (name.equals("commit") || name.equals("rollback")
                                    || name.equals("setAutoCommit") || name.equals("execute")) {
                                handler.handle(Future.succeededFuture(null));
                            } else if (name.contains("update") || name.contains("Update")) {
                                handler.handle(Future.succeededFuture(
                                        ur != null ? ur : new UpdateResult()));
                            } else if (name.contains("batch") || name.contains("Batch")) {
                                handler.handle(Future.succeededFuture(Collections.emptyList()));
                            } else if (name.contains("querySingle") || name.contains("Single")) {
                                handler.handle(Future.succeededFuture(null)); // Maybe.empty
                            } else {
                                // query, queryStream, call, etc.
                                handler.handle(Future.succeededFuture(rs));
                            }
                            return proxy;
                        }
                    }
                    return proxy;
                });
    }

    @SuppressWarnings("unchecked")
    private static SQLClient stubClient(SQLConnection connection) {
        return (SQLClient) Proxy.newProxyInstance(
                SQLClient.class.getClassLoader(),
                new Class<?>[]{SQLClient.class},
                (proxy, method, args) -> {
                    String name = method.getName();

                    if ("close".equals(name)) {
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof Handler) {
                                    ((Handler<AsyncResult<Void>>) arg).handle(
                                            Future.succeededFuture());
                                }
                            }
                        }
                        return null;
                    }

                    if ("getConnection".equals(name)) {
                        Handler<AsyncResult<SQLConnection>> handler =
                                (Handler<AsyncResult<SQLConnection>>) args[args.length - 1];
                        handler.handle(Future.succeededFuture(connection));
                        return proxy;
                    }

                    return proxy;
                });
    }
}
