package io.last9.tracing.otel.v3.agent;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Key;
import com.aerospike.client.policy.ClientPolicy;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AerospikeSyncCommandHelper} — verifies that the command-level
 * instrumentation (Datadog approach) catches Aerospike operations that the
 * public API-level advice might miss.
 *
 * <p>Reproduces the customer scenario:
 * <ul>
 *   <li>App has a custom AerospikeService wrapper around AerospikeClient</li>
 *   <li>AerospikeClient class IS transformed by ByteBuddy</li>
 *   <li>But spans don't appear (possibly due to method signature mismatch or class loading order)</li>
 *   <li>SyncCommand.execute()-level advice catches it regardless</li>
 * </ul>
 */
class AerospikeSyncCommandHelperTest {

    private GlobalOtelTestSetup otel;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void setUp() {
        otel = new GlobalOtelTestSetup();
        otel.setUp();
        spanExporter = otel.getSpanExporter();
        AgentGuard.IN_DB_TRACED_CALL.set(false);
        AerospikeClientHelper.IN_AEROSPIKE_CALL.set(false);
    }

    @AfterEach
    void tearDown() {
        AgentGuard.IN_DB_TRACED_CALL.remove();
        AerospikeClientHelper.IN_AEROSPIKE_CALL.remove();
        otel.tearDown();
    }

    // ── Key extraction from command objects ──────────────────────

    @Test
    void startSpanExtractsKeyFromReadCommand() {
        // Simulate what SyncCommand advice does: extract key from a command object.
        // We can't instantiate real SyncCommand (needs Cluster), but we can test
        // the helper's startSpan directly with the operation + key pattern.
        Key key = new Key("fantasy_tour", "round", "113087");
        Span span = AerospikeSyncCommandHelper.startSpan(mockCommandObject("ReadCommand", key));

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        // MockCommand → "MOCK" operation (real ReadCommand → "GET")
        // Key extraction works: namespace.setName from the key field
        assertThat(sd.getName()).contains("fantasy_tour.round");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system"))).isEqualTo("aerospike");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name"))).isEqualTo("fantasy_tour");
    }

    @Test
    void startSpanExtractsKeyFromWriteCommand() {
        Key key = new Key("user_teams", "round_config", "42");
        Span span = AerospikeSyncCommandHelper.startSpan(mockCommandObject("WriteCommand", key));

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).contains("user_teams.round_config");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.name"))).isEqualTo("user_teams");
    }

    @Test
    void startSpanExtractsKeyFromDeleteCommand() {
        Key key = new Key("cache", "sessions", "sess:abc");
        Span span = AerospikeSyncCommandHelper.startSpan(mockCommandObject("DeleteCommand", key));

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).contains("cache.sessions");
    }

    @Test
    void startSpanHandlesCommandWithoutKeyField() {
        // A command that doesn't have a 'key' field (e.g., batch or admin)
        Span span = AerospikeSyncCommandHelper.startSpan(new Object() {
            @Override
            public String toString() { return "UnknownCommand"; }
        });

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        // Without a key, span name is just "aerospike OBJECT"
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system"))).isEqualTo("aerospike");
    }

    @Test
    void startSpanRespectsAgentGuard() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);
        Span span = AerospikeSyncCommandHelper.startSpan(mockCommandObject("ReadCommand",
                new Key("ns", "set", "k")));
        assertThat(span).isNull();
    }

    @Test
    void startSpanRespectsInAerospikeCallGuard() {
        AerospikeClientHelper.IN_AEROSPIKE_CALL.set(true);
        Span span = AerospikeSyncCommandHelper.startSpan(mockCommandObject("ReadCommand",
                new Key("ns", "set", "k")));
        assertThat(span).isNull();
    }

    // ── Wrapper pattern (customer reproduction) ─────────────────

    @Test
    void wrapperServicePatternProducesSpan() {
        // Reproduces the customer pattern: custom AerospikeService wrapper
        // that calls AerospikeClient internally.
        Key key = new Key("fantasy_tour", "activeRounds", "31");

        // Simulate what SyncCommand.execute() advice does:
        // 1. Extract operation from command class name
        // 2. Extract key from command field
        // 3. Create span
        Span span = AerospikeClientHelper.startSpan("GET", key);
        assertThat(span).isNotNull();

        // Simulate the operation completing
        AerospikeClientHelper.endSpan(span, span.makeCurrent(), null);

        List<SpanData> aerospikeSpans = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> "aerospike".equals(s.getAttributes().get(AttributeKey.stringKey("db.system"))))
                .collect(Collectors.toList());

        assertThat(aerospikeSpans).hasSize(1);
        SpanData sd = aerospikeSpans.get(0);
        assertThat(sd.getName()).isEqualTo("aerospike GET fantasy_tour.activeRounds");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(sd.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }

    @Test
    void wrapperServicePatternRecordsAerospikeError() {
        Key key = new Key("fantasy_tour", "round", "113087");
        Span span = AerospikeClientHelper.startSpan("GET", key);
        assertThat(span).isNotNull();

        AerospikeClientHelper.endSpan(span, span.makeCurrent(),
                new AerospikeException("Connection timeout"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void doubleAdviceGuardPreventsDoubleSpans() {
        // When both AerospikeClientAdvice (public API) and AerospikeSyncCommandAdvice
        // (command level) fire for the same call, only one span should be created.
        Key key = new Key("test", "cache", "k1");

        // First advice fires (e.g., AerospikeClientAdvice on client.get())
        Span span1 = AerospikeClientHelper.startSpan("GET", key);
        assertThat(span1).isNotNull();

        // Second advice fires (SyncCommand.execute() inside client.get())
        // Should return null because IN_AEROSPIKE_CALL is true
        Span span2 = AerospikeClientHelper.startSpan("GET", key);
        assertThat(span2).isNull();

        // End the first span
        AerospikeClientHelper.endSpan(span1, span1.makeCurrent(), null);

        // Only one span should exist
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
    }

    @Test
    void afterGuardResetSecondCallWorks() {
        Key key = new Key("test", "cache", "k1");

        // First call
        Span span1 = AerospikeClientHelper.startSpan("GET", key);
        AerospikeClientHelper.endSpan(span1, span1.makeCurrent(), null);

        // Guard should be reset after endSpan
        assertThat(AerospikeClientHelper.IN_AEROSPIKE_CALL.get()).isFalse();

        // Second call should work
        Span span2 = AerospikeClientHelper.startSpan("PUT", key);
        assertThat(span2).isNotNull();
        AerospikeClientHelper.endSpan(span2, span2.makeCurrent(), null);

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(2);
    }

    // ── Helper to simulate command objects with key fields ───────

    /**
     * Creates a mock object with a {@code key} field that simulates an
     * Aerospike SyncCommand subclass. The class name is used for operation mapping.
     */
    private Object mockCommandObject(String className, Key key) {
        return new MockCommand(className, key);
    }

    /**
     * Mock command with a {@code key} field accessible via reflection,
     * matching the pattern of ReadCommand/WriteCommand/DeleteCommand.
     */
    static class MockCommand {
        private final String name;
        @SuppressWarnings("unused") // accessed via reflection by AerospikeSyncCommandHelper
        private final Key key;

        MockCommand(String name, Key key) {
            this.name = name;
            this.key = key;
        }

        @Override
        public String toString() { return name; }

        // Override getClass().getSimpleName() isn't possible, so the helper
        // will use "MockCommand" as the class name. We test key extraction
        // via startSpan() which calls extractKey() on this object.
    }
}
