package io.last9.tracing.otel.v3;

import io.last9.tracing.otel.v3.agent.AgentGuard;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link DbTracing} trace methods set the {@link AgentGuard#IN_DB_TRACED_CALL}
 * guard during supplier execution, preventing double-instrumentation by bytecode advice.
 */
class DbTracingGuardTest {

    private TestOtelSetup otel;
    private DbTracing db;

    @BeforeEach
    void setUp() {
        otel = new TestOtelSetup();
        db = DbTracing.create("mysql", "test_db", otel.getOpenTelemetry());
        AgentGuard.IN_DB_TRACED_CALL.set(false);
    }

    @AfterEach
    void tearDown() {
        AgentGuard.IN_DB_TRACED_CALL.remove();
        otel.shutdown();
    }

    @Test
    void traceSingleSetsGuardDuringSupplierExecution() {
        AtomicBoolean guardWasSet = new AtomicBoolean(false);

        db.traceSingle("SELECT 1", () -> {
            guardWasSet.set(AgentGuard.IN_DB_TRACED_CALL.get());
            return Single.just(1);
        }).blockingGet();

        assertThat(guardWasSet.get()).isTrue();
        // Guard must be cleared after
        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }

    @Test
    void traceCompletableSetsGuardDuringSupplierExecution() {
        AtomicBoolean guardWasSet = new AtomicBoolean(false);

        db.traceCompletable("DELETE FROM cache", () -> {
            guardWasSet.set(AgentGuard.IN_DB_TRACED_CALL.get());
            return Completable.complete();
        }).blockingAwait();

        assertThat(guardWasSet.get()).isTrue();
        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }

    @Test
    void traceMaybeSetsGuardDuringSupplierExecution() {
        AtomicBoolean guardWasSet = new AtomicBoolean(false);

        db.traceMaybe("GET key:1", () -> {
            guardWasSet.set(AgentGuard.IN_DB_TRACED_CALL.get());
            return Maybe.just("value");
        }).blockingGet();

        assertThat(guardWasSet.get()).isTrue();
        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }

    @Test
    void traceSyncSetsGuardDuringSupplierExecution() {
        AtomicBoolean guardWasSet = new AtomicBoolean(false);

        db.traceSync("GET user:123", () -> {
            guardWasSet.set(AgentGuard.IN_DB_TRACED_CALL.get());
            return "found";
        });

        assertThat(guardWasSet.get()).isTrue();
        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }

    @Test
    void traceSingleClearsGuardOnError() {
        try {
            db.traceSingle("BAD", () -> {
                throw new RuntimeException("boom");
            }).blockingGet();
        } catch (RuntimeException ignored) {}

        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }

    @Test
    void traceSyncClearsGuardOnError() {
        try {
            db.traceSync("BAD", () -> {
                throw new RuntimeException("boom");
            });
        } catch (RuntimeException ignored) {}

        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }

    @Test
    void traceCompletableClearsGuardOnError() {
        try {
            db.traceCompletable("BAD", () -> {
                throw new RuntimeException("boom");
            }).blockingAwait();
        } catch (RuntimeException ignored) {}

        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }

    @Test
    void traceMaybeClearsGuardOnError() {
        try {
            db.traceMaybe("BAD", () -> {
                throw new RuntimeException("boom");
            }).blockingGet();
        } catch (RuntimeException ignored) {}

        assertThat(AgentGuard.IN_DB_TRACED_CALL.get()).isFalse();
    }
}
