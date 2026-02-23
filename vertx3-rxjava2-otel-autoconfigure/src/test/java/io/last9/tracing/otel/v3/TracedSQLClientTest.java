package io.last9.tracing.otel.v3;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
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
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracedSQLClientTest {

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

    private io.vertx.reactivex.ext.sql.SQLClient createTraced(ResultSet rs, UpdateResult ur,
                                                                RuntimeException error) {
        SQLClient stub = createStubSQLClient(rs, ur, error, null);
        return TracedSQLClient.wrap(
                io.vertx.reactivex.ext.sql.SQLClient.newInstance(stub),
                "mysql", "orders_db", otel.getOpenTelemetry());
    }

    @Test
    void rxQueryCreatesClientSpan() {
        ResultSet rs = new ResultSet();
        rs.setColumnNames(Collections.singletonList("id"));
        rs.setResults(Collections.singletonList(new JsonArray().add(1)));

        io.vertx.reactivex.ext.sql.SQLClient traced = createTraced(rs, null, null);
        traced.rxQuery("SELECT * FROM orders").blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("mysql SELECT * FROM orders");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("mysql");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("SELECT * FROM orders");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("orders_db");
    }

    @Test
    void rxUpdateWithParamsCreatesSpan() {
        io.vertx.reactivex.ext.sql.SQLClient traced = createTraced(null, new UpdateResult(), null);

        traced.rxUpdateWithParams("INSERT INTO users (name) VALUES (?)",
                new JsonArray().add("Alice")).blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).contains("INSERT INTO users");
    }

    @Test
    void rxQuerySingleUsesMaybe() {
        io.vertx.reactivex.ext.sql.SQLClient traced = createTraced(null, null, null);

        // querySingle returns Maybe — completes empty, still produces a span
        traced.rxQuerySingle("SELECT 1").blockingGet();

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void errorRecordedOnSpan() {
        io.vertx.reactivex.ext.sql.SQLClient traced =
                createTraced(null, null, new RuntimeException("connection refused"));

        assertThatThrownBy(() -> traced.rxQuery("SELECT 1").blockingGet())
                .isInstanceOf(RuntimeException.class);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void rxGetConnectionReturnsTracedConnection() {
        ResultSet rs = new ResultSet();
        rs.setColumnNames(Collections.singletonList("id"));
        rs.setResults(Collections.singletonList(new JsonArray().add(1)));

        SQLConnection stubConn = createStubSQLConnection(rs, null, null);
        SQLClient stubClient = createStubSQLClient(null, null, null, stubConn);

        io.vertx.reactivex.ext.sql.SQLClient traced = TracedSQLClient.wrap(
                io.vertx.reactivex.ext.sql.SQLClient.newInstance(stubClient),
                "mysql", "orders_db", otel.getOpenTelemetry());

        io.vertx.reactivex.ext.sql.SQLConnection conn = traced.rxGetConnection().blockingGet();
        conn.rxQuery("SELECT * FROM users").blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("mysql SELECT * FROM users");
    }

    @Test
    void connectionCommitCreatesSpan() {
        SQLConnection stubConn = createStubSQLConnection(null, null, null);
        SQLClient stubClient = createStubSQLClient(null, null, null, stubConn);

        io.vertx.reactivex.ext.sql.SQLClient traced = TracedSQLClient.wrap(
                io.vertx.reactivex.ext.sql.SQLClient.newInstance(stubClient),
                "mysql", "db", otel.getOpenTelemetry());

        io.vertx.reactivex.ext.sql.SQLConnection conn = traced.rxGetConnection().blockingGet();
        conn.rxCommit().blockingAwait();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("mysql COMMIT");
    }

    @Test
    void rxCloseDoesNotCreateSpan() {
        io.vertx.reactivex.ext.sql.SQLClient traced = createTraced(null, null, null);
        traced.rxClose().blockingAwait();

        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    // ---- Dynamic proxy stubs ----

    @SuppressWarnings("unchecked")
    private static SQLClient createStubSQLClient(ResultSet rs, UpdateResult ur,
                                                  RuntimeException error,
                                                  SQLConnection connection) {
        return (SQLClient) Proxy.newProxyInstance(
                SQLClient.class.getClassLoader(),
                new Class<?>[]{SQLClient.class},
                (proxy, method, args) -> {
                    String name = method.getName();

                    // close methods
                    if ("close".equals(name)) {
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof Handler) {
                                    ((Handler<AsyncResult<Void>>) arg).handle(Future.succeededFuture());
                                }
                            }
                        }
                        return null;
                    }

                    // getConnection
                    if ("getConnection".equals(name) && connection != null) {
                        Handler<AsyncResult<SQLConnection>> handler =
                                (Handler<AsyncResult<SQLConnection>>) args[args.length - 1];
                        handler.handle(Future.succeededFuture(connection));
                        return proxy;
                    }

                    // Find the Handler callback (last arg)
                    if (args != null) {
                        Object lastArg = args[args.length - 1];
                        if (lastArg instanceof Handler) {
                            Handler handler = (Handler) lastArg;
                            if (error != null) {
                                handler.handle(Future.failedFuture(error));
                            } else {
                                // Return appropriate result based on method name
                                if (name.contains("update") || name.equals("update")) {
                                    handler.handle(Future.succeededFuture(
                                            ur != null ? ur : new UpdateResult()));
                                } else if (name.contains("querySingle")) {
                                    handler.handle(Future.succeededFuture(null)); // Maybe.empty
                                } else {
                                    handler.handle(Future.succeededFuture(rs));
                                }
                            }
                            return proxy;
                        }
                    }

                    return proxy;
                });
    }

    @SuppressWarnings("unchecked")
    private static SQLConnection createStubSQLConnection(ResultSet rs, UpdateResult ur,
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
                        Object lastArg = args[args.length - 1];
                        if (lastArg instanceof Handler) {
                            Handler handler = (Handler) lastArg;
                            if (error != null) {
                                handler.handle(Future.failedFuture(error));
                            } else if (name.equals("commit") || name.equals("rollback")
                                    || name.equals("setAutoCommit") || name.equals("execute")) {
                                handler.handle(Future.succeededFuture(null));
                            } else if (name.contains("update")) {
                                handler.handle(Future.succeededFuture(
                                        ur != null ? ur : new UpdateResult()));
                            } else if (name.contains("batch")) {
                                handler.handle(Future.succeededFuture(Collections.emptyList()));
                            } else if (name.contains("querySingle")) {
                                handler.handle(Future.succeededFuture(null));
                            } else {
                                handler.handle(Future.succeededFuture(rs));
                            }
                            return proxy;
                        }
                    }

                    return proxy;
                });
    }
}
