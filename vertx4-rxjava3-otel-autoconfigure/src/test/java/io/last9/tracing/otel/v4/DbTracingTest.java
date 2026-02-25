package io.last9.tracing.otel.v4;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbTracingTest {

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

    // ---- traceSingle ----

    @Test
    void traceSingleCreatesClientSpan() {
        db("postgresql", "orders_db")
                .traceSingle("SELECT * FROM orders", () -> Single.just("result"))
                .blockingGet();

        SpanData span = single();
        assertThat(span.getName()).isEqualTo("postgresql SELECT * FROM orders");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
    }

    @Test
    void traceSingleSetsDbAttributes() {
        db("postgresql", "orders_db")
                .traceSingle("SELECT 1", () -> Single.just(1))
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
    void traceSingleOmitsDbNameWhenNull() {
        DbTracing.create("postgresql", null, otel.getOpenTelemetry())
                .traceSingle("SELECT 1", () -> Single.just(1))
                .blockingGet();

        SpanData span = single();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.name"))).isNull();
    }

    @Test
    void traceSingleSpanIsCurrentDuringOperation() {
        AtomicReference<String> capturedSpanId = new AtomicReference<>();

        db("mysql", "db")
                .traceSingle("SELECT 1", () -> Single.fromCallable(() -> {
                    capturedSpanId.set(Span.current().getSpanContext().getSpanId());
                    return "ok";
                }))
                .blockingGet();

        SpanData span = single();
        assertThat(capturedSpanId.get()).isEqualTo(span.getSpanId());
    }

    @Test
    void traceSingleRecordsExceptionAndSetsErrorStatus() {
        RuntimeException err = new RuntimeException("connection refused");

        assertThatThrownBy(() ->
                db("postgresql", "db")
                        .traceSingle("SELECT 1", () -> Single.error(err))
                        .blockingGet()
        ).isInstanceOf(RuntimeException.class);

        SpanData span = single();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo("connection refused");
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void traceSingleEndsSpanOnDisposal() {
        var disposable = db("postgresql", "db")
                .traceSingle("SELECT 1", () -> Single.never())
                .subscribe();

        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
        disposable.dispose();
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    // ---- traceCompletable ----

    @Test
    void traceCompletableCreatesClientSpan() {
        db("postgresql", "db")
                .traceCompletable("DELETE FROM cache WHERE expired = true",
                        () -> Completable.complete())
                .blockingAwait();

        SpanData span = single();
        assertThat(span.getName()).isEqualTo("postgresql DELETE FROM cache WHERE expired = true");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
    }

    @Test
    void traceCompletableRecordsExceptionOnError() {
        RuntimeException err = new RuntimeException("timeout");

        assertThatThrownBy(() ->
                db("postgresql", "db")
                        .traceCompletable("DELETE FROM cache", () -> Completable.error(err))
                        .blockingAwait()
        ).isInstanceOf(RuntimeException.class);

        SpanData span = single();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    // ---- traceMaybe ----

    @Test
    void traceMaybeCreatesClientSpanWhenValueEmitted() {
        db("mysql", "db")
                .traceMaybe("SELECT name FROM users WHERE id = 1", () -> Maybe.just("Alice"))
                .blockingGet();

        SpanData span = single();
        assertThat(span.getName()).isEqualTo("mysql SELECT name FROM users WHERE id = 1");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
    }

    @Test
    void traceMaybeCreatesSpanEvenWhenEmpty() {
        db("mysql", "db")
                .traceMaybe("SELECT name FROM users WHERE id = 99", () -> Maybe.empty())
                .blockingGet();

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void traceMaybeRecordsExceptionOnError() {
        RuntimeException err = new RuntimeException("query failed");

        assertThatThrownBy(() ->
                db("mysql", "db")
                        .traceMaybe("SELECT 1", () -> Maybe.error(err))
                        .blockingGet()
        ).isInstanceOf(RuntimeException.class);

        SpanData span = single();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    // ---- Helpers ----

    private DbTracing db(String system, String namespace) {
        return DbTracing.create(system, namespace, otel.getOpenTelemetry());
    }

    private SpanData single() {
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).as("expected exactly one span").hasSize(1);
        return spans.get(0);
    }
}
