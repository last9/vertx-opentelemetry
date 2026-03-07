package io.last9.tracing.otel.v3;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbTracingTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;
    private DbTracing db;

    @BeforeEach
    void setUp() {
        otel = new TestOtelSetup();
        spanExporter = otel.getSpanExporter();
        db = DbTracing.create("mysql", "orders_db", otel.getOpenTelemetry());
    }

    @AfterEach
    void tearDown() {
        otel.shutdown();
    }

    @Test
    void traceSingleCreatesClientSpan() {
        String traceId = db.traceSingle("SELECT * FROM orders",
                () -> Single.fromCallable(() -> Span.current().getSpanContext().getTraceId()))
                .blockingGet();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("SELECT orders_db.orders");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(traceId).isEqualTo(span.getTraceId());
        assertThat(traceId).isNotEqualTo("00000000000000000000000000000000");
    }

    @Test
    void traceSingleSetsDbAttributes() {
        db.traceSingle("SELECT 1", () -> Single.just(1)).blockingGet();

        SpanData span = spanExporter.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("mysql");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("SELECT 1");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name")))
                .isEqualTo("orders_db");
    }

    @Test
    void traceSingleRecordsExceptionOnError() {
        assertThatThrownBy(() ->
                db.traceSingle("BAD QUERY", () -> Single.error(new RuntimeException("syntax error")))
                        .blockingGet()
        ).isInstanceOf(RuntimeException.class);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void traceCompletableCreatesSpan() {
        db.traceCompletable("DELETE FROM cache", () -> Completable.complete()).blockingAwait();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("DELETE orders_db.cache");
        assertThat(spans.get(0).getKind()).isEqualTo(SpanKind.CLIENT);
    }

    @Test
    void traceMaybeCreatesSpanOnSuccess() {
        String result = db.traceMaybe("GET key:1",
                () -> Maybe.just("value")).blockingGet();

        assertThat(result).isEqualTo("value");
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void traceMaybeCreatesSpanOnEmpty() {
        db.traceMaybe("GET key:missing", () -> Maybe.empty()).blockingGet();

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void traceSyncCreatesSpan() {
        DbTracing aerospike = DbTracing.create("aerospike", "test-ns", otel.getOpenTelemetry());

        String result = aerospike.traceSync("GET user:123", () -> "found");

        assertThat(result).isEqualTo("found");
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("aerospike GET user:123");
        assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("aerospike");
    }

    @Test
    void traceSyncRecordsExceptionAndRethrows() {
        assertThatThrownBy(() ->
                db.traceSync("BAD OP", () -> { throw new RuntimeException("connection failed"); })
        ).isInstanceOf(RuntimeException.class).hasMessage("connection failed");

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
        assertThat(spanExporter.getFinishedSpanItems().get(0).getEvents())
                .anyMatch(e -> e.getName().equals("exception"));
    }
}
