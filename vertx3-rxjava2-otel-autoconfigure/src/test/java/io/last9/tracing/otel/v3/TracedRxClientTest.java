package io.last9.tracing.otel.v3;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link TracedRxClient} wraps any RxJava2 interface with CLIENT spans.
 */
class TracedRxClientTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;

    /**
     * Simulates a third-party client interface (e.g., a custom MysqlClient).
     */
    interface FakeDbClient {
        Single<String> rxQuery(String sql);
        Completable rxExecute(String sql);
        Maybe<String> rxFind(String key);
        String getName();  // Non-Rx method — should pass through
    }

    @BeforeEach
    void setUp() {
        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();
    }

    @AfterEach
    void tearDown() {
        otel.shutdown();
    }

    @Test
    void wrapsSingleMethodWithClientSpan() {
        FakeDbClient raw = new FakeDbClientImpl();
        FakeDbClient traced = TracedRxClient.wrap(
                raw, FakeDbClient.class, "mysql", "orders_db", otel.getOpenTelemetry());

        String result = traced.rxQuery("SELECT * FROM users").blockingGet();

        assertThat(result).isEqualTo("query-result");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getName()).isEqualTo("mysql rxQuery");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("mysql");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("orders_db");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("rxQuery");
    }

    @Test
    void wrapsCompletableMethodWithClientSpan() {
        FakeDbClient raw = new FakeDbClientImpl();
        FakeDbClient traced = TracedRxClient.wrap(
                raw, FakeDbClient.class, "mysql", "orders_db", otel.getOpenTelemetry());

        traced.rxExecute("INSERT INTO users VALUES (1, 'Alice')").blockingAwait();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getName()).isEqualTo("mysql rxExecute");
    }

    @Test
    void wrapsMaybeMethodWithClientSpan() {
        FakeDbClient raw = new FakeDbClientImpl();
        FakeDbClient traced = TracedRxClient.wrap(
                raw, FakeDbClient.class, "aerospike", "my-ns", otel.getOpenTelemetry());

        String result = traced.rxFind("user:123").blockingGet();

        assertThat(result).isEqualTo("found-user:123");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("aerospike rxFind");
        assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("aerospike");
    }

    @Test
    void nonRxMethodPassesThroughWithoutSpan() {
        FakeDbClient raw = new FakeDbClientImpl();
        FakeDbClient traced = TracedRxClient.wrap(
                raw, FakeDbClient.class, "mysql", "mydb", otel.getOpenTelemetry());

        String name = traced.getName();

        assertThat(name).isEqualTo("fake-db-client");
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void recordsExceptionOnSingleError() {
        FakeDbClient raw = new FailingDbClientImpl();
        FakeDbClient traced = TracedRxClient.wrap(
                raw, FakeDbClient.class, "mysql", "mydb", otel.getOpenTelemetry());

        try {
            traced.rxQuery("BAD SQL").blockingGet();
        } catch (Exception ignored) {
        }

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void customOperationNameFunction() {
        FakeDbClient raw = new FakeDbClientImpl();
        FakeDbClient traced = TracedRxClient.wrap(
                raw, FakeDbClient.class, "mysql", "orders_db",
                otel.getOpenTelemetry(),
                (method, args) -> method.getName() + " " + (args != null && args.length > 0 ? args[0] : ""));

        traced.rxQuery("SELECT * FROM users").blockingGet();

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        // Custom name includes the SQL statement
        assertThat(span.getName()).isEqualTo("mysql rxQuery SELECT * FROM users");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("rxQuery SELECT * FROM users");
    }

    @Test
    void multipleCallsProduceMultipleSpans() {
        FakeDbClient raw = new FakeDbClientImpl();
        FakeDbClient traced = TracedRxClient.wrap(
                raw, FakeDbClient.class, "mysql", "mydb", otel.getOpenTelemetry());

        traced.rxQuery("SELECT 1").blockingGet();
        traced.rxExecute("DELETE FROM temp").blockingAwait();
        traced.rxFind("key1").blockingGet();

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(3);
    }

    // ---- Test implementations ----

    static class FakeDbClientImpl implements FakeDbClient {
        @Override
        public Single<String> rxQuery(String sql) {
            return Single.just("query-result");
        }

        @Override
        public Completable rxExecute(String sql) {
            return Completable.complete();
        }

        @Override
        public Maybe<String> rxFind(String key) {
            return Maybe.just("found-" + key);
        }

        @Override
        public String getName() {
            return "fake-db-client";
        }
    }

    static class FailingDbClientImpl implements FakeDbClient {
        @Override
        public Single<String> rxQuery(String sql) {
            return Single.error(new RuntimeException("query failed"));
        }

        @Override
        public Completable rxExecute(String sql) {
            return Completable.error(new RuntimeException("execute failed"));
        }

        @Override
        public Maybe<String> rxFind(String key) {
            return Maybe.error(new RuntimeException("find failed"));
        }

        @Override
        public String getName() {
            return "failing-client";
        }
    }
}
