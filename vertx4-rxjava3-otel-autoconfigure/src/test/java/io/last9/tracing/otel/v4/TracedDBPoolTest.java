package io.last9.tracing.otel.v4;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.rxjava3.sqlclient.Pool;
import io.vertx.rxjava3.sqlclient.Tuple;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracedDBPoolTest {

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

    // ---- query ----

    @Test
    void queryCreatesClientSpan() {
        pool("postgresql", "orders_db", null)
                .query("SELECT * FROM orders")
                .blockingGet();

        SpanData span = single();
        assertThat(span.getName()).isEqualTo("postgresql SELECT * FROM orders");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
    }

    @Test
    void querySpanHasDbSystemAndStatementAttributes() {
        pool("postgresql", "orders_db", null)
                .query("SELECT 1")
                .blockingGet();

        SpanData span = single();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("postgresql");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("SELECT 1");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("orders_db");
    }

    @Test
    void queryWithoutDbNameOmitsDbNameAttribute() {
        TracedDBPool.wrap(stubPool(null), "postgresql", null, otel.getOpenTelemetry())
                .query("SELECT 1")
                .blockingGet();

        SpanData span = single();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name"))).isNull();
    }

    @Test
    void queryErrorRecordsExceptionAndSetsErrorStatus() {
        RuntimeException err = new RuntimeException("connection refused");

        assertThatThrownBy(() ->
                pool("postgresql", "db", err)
                        .query("SELECT 1")
                        .blockingGet()
        ).isInstanceOf(RuntimeException.class);

        SpanData span = single();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    // ---- preparedQuery ----

    @Test
    void preparedQueryWithArgsCreatesClientSpan() {
        pool("postgresql", "orders_db", null)
                .preparedQuery("SELECT * FROM orders WHERE id = $1", Tuple.of(42))
                .blockingGet();

        SpanData span = single();
        assertThat(span.getName()).isEqualTo("postgresql SELECT * FROM orders WHERE id = $1");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
    }

    @Test
    void preparedQueryNoArgsCreatesClientSpan() {
        pool("postgresql", "orders_db", null)
                .preparedQuery("SELECT COUNT(*) FROM orders")
                .blockingGet();

        SpanData span = single();
        assertThat(span.getName()).isEqualTo("postgresql SELECT COUNT(*) FROM orders");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
    }

    @Test
    void preparedQueryErrorSetsErrorStatus() {
        RuntimeException err = new RuntimeException("syntax error");

        assertThatThrownBy(() ->
                pool("postgresql", "db", err)
                        .preparedQuery("SELECT bad syntax")
                        .blockingGet()
        ).isInstanceOf(RuntimeException.class);

        SpanData span = single();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    // ---- unwrap ----

    @Test
    void unwrapReturnsUnderlyingPool() {
        Pool underlying = stubPool(null);
        TracedDBPool traced = TracedDBPool.wrap(underlying, "postgresql", "db", otel.getOpenTelemetry());
        assertThat(traced.unwrap()).isSameAs(underlying);
    }

    // ---- Helpers ----

    private TracedDBPool pool(String system, String dbName, RuntimeException error) {
        return TracedDBPool.wrap(stubPool(error), system, dbName, otel.getOpenTelemetry());
    }

    private SpanData single() {
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).as("expected exactly one span").hasSize(1);
        return spans.get(0);
    }

    /**
     * Stubs {@code io.vertx.sqlclient.Pool} (core interface) and wraps it in the
     * rxjava3 {@link Pool} wrapper via {@code Pool.newInstance()}.
     *
     * <p>On success, {@code query().execute(handler)} and
     * {@code preparedQuery().execute(handler/tuple, handler)} fire the handler with a minimal
     * stub {@link RowSet}. On error, the handler fires with a failed {@link Future}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Pool stubPool(RuntimeException error) {
        RowSet<Row> stubRows = stubRowSet();

        // Stub for Query<RowSet<Row>>
        io.vertx.sqlclient.Query<RowSet<Row>> stubQuery =
                (io.vertx.sqlclient.Query<RowSet<Row>>) Proxy.newProxyInstance(
                        io.vertx.sqlclient.Query.class.getClassLoader(),
                        new Class<?>[]{io.vertx.sqlclient.Query.class},
                        (proxy, method, args) -> {
                            if ("execute".equals(method.getName())) {
                                if (args != null && args[args.length - 1] instanceof Handler) {
                                    Handler handler = (Handler) args[args.length - 1];
                                    if (error != null) handler.handle(Future.failedFuture(error));
                                    else handler.handle(Future.succeededFuture(stubRows));
                                    return null;
                                }
                                // Future-returning overload
                                if (error != null) return Future.failedFuture(error);
                                return Future.succeededFuture(stubRows);
                            }
                            return null;
                        });

        // Stub for PreparedQuery<RowSet<Row>>
        io.vertx.sqlclient.PreparedQuery<RowSet<Row>> stubPrepared =
                (io.vertx.sqlclient.PreparedQuery<RowSet<Row>>) Proxy.newProxyInstance(
                        io.vertx.sqlclient.PreparedQuery.class.getClassLoader(),
                        new Class<?>[]{io.vertx.sqlclient.PreparedQuery.class},
                        (proxy, method, args) -> {
                            if ("execute".equals(method.getName())) {
                                if (args != null && args[args.length - 1] instanceof Handler) {
                                    Handler handler = (Handler) args[args.length - 1];
                                    if (error != null) handler.handle(Future.failedFuture(error));
                                    else handler.handle(Future.succeededFuture(stubRows));
                                    return null;
                                }
                                if (error != null) return Future.failedFuture(error);
                                return Future.succeededFuture(stubRows);
                            }
                            return null;
                        });

        // Stub for Pool (core interface)
        io.vertx.sqlclient.Pool corePool =
                (io.vertx.sqlclient.Pool) Proxy.newProxyInstance(
                        io.vertx.sqlclient.Pool.class.getClassLoader(),
                        new Class<?>[]{io.vertx.sqlclient.Pool.class},
                        (proxy, method, args) -> {
                            if ("query".equals(method.getName())) return stubQuery;
                            if ("preparedQuery".equals(method.getName())) return stubPrepared;
                            if ("close".equals(method.getName())) {
                                if (args != null && args.length > 0 && args[0] instanceof Handler) {
                                    ((Handler<AsyncResult<Void>>) args[0]).handle(
                                            Future.succeededFuture());
                                }
                                return null;
                            }
                            return null;
                        });

        return Pool.newInstance(corePool);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RowSet<Row> stubRowSet() {
        return (RowSet<Row>) Proxy.newProxyInstance(
                RowSet.class.getClassLoader(),
                new Class<?>[]{RowSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("iterator".equals(name)) return Collections.emptyIterator();
                    if ("size".equals(name) || "rowCount".equals(name)) return 0;
                    if ("next".equals(name)) return null;
                    if ("columnsNames".equals(name)) return Collections.emptyList();
                    return null;
                });
    }
}
