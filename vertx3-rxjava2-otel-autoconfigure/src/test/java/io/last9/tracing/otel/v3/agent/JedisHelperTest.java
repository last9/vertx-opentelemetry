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

class JedisHelperTest {

    private GlobalOtelTestSetup otel;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void setUp() {
        otel = new GlobalOtelTestSetup();
        otel.setUp();
        spanExporter = otel.getSpanExporter();
        AgentGuard.IN_DB_TRACED_CALL.set(false);
    }

    @AfterEach
    void tearDown() {
        AgentGuard.IN_DB_TRACED_CALL.remove();
        otel.tearDown();
    }

    /**
     * Stub that mimics Jedis Protocol.Command enum — has a name() method
     * returning the command name (like a Java enum).
     */
    @SuppressWarnings("unused")
    static class StubProtocolCommand {
        private final String commandName;
        StubProtocolCommand(String commandName) { this.commandName = commandName; }
        public String name() { return commandName; }
    }

    /**
     * Stub that only has getRaw() — simulates ProtocolCommand implementations
     * that don't expose name() but have getRaw() returning byte[].
     */
    @SuppressWarnings("unused")
    static class StubRawCommand {
        private final byte[] raw;
        StubRawCommand(String commandName) { this.raw = commandName.getBytes(); }
        public byte[] getRaw() { return raw; }
    }

    @Test
    void startSpanCreatesClientSpanForGetCommand() {
        Span span = JedisHelper.startSpan(new StubProtocolCommand("GET"));

        assertThat(span).isNotNull();
        span.end();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getName()).isEqualTo("redis GET");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.system")))
                .isEqualTo("redis");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("db.statement")))
                .isEqualTo("GET");
    }

    @Test
    void startSpanCreatesSpanForSetCommand() {
        Span span = JedisHelper.startSpan(new StubProtocolCommand("SET"));
        assertThat(span).isNotNull();
        span.end();

        assertThat(spanExporter.getFinishedSpanItems().get(0).getName())
                .isEqualTo("redis SET");
    }

    @Test
    void startSpanCreatesSpanForHgetallCommand() {
        Span span = JedisHelper.startSpan(new StubProtocolCommand("HGETALL"));
        assertThat(span).isNotNull();
        span.end();

        assertThat(spanExporter.getFinishedSpanItems().get(0).getName())
                .isEqualTo("redis HGETALL");
    }

    @Test
    void startSpanReturnsNullWhenGuardIsSet() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);

        Span span = JedisHelper.startSpan(new StubProtocolCommand("GET"));

        assertThat(span).isNull();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void startSpanHandlesNullCommand() {
        Span span = JedisHelper.startSpan(null);
        assertThat(span).isNotNull();
        span.end();

        assertThat(spanExporter.getFinishedSpanItems().get(0).getName())
                .isEqualTo("redis UNKNOWN");
    }

    @Test
    void startSpanFallsBackToGetRawWhenNameFails() {
        // StubRawCommand has no name() method, only getRaw()
        Span span = JedisHelper.startSpan(new StubRawCommand("lpush"));
        assertThat(span).isNotNull();
        span.end();

        assertThat(spanExporter.getFinishedSpanItems().get(0).getName())
                .isEqualTo("redis LPUSH");
    }

    @Test
    void endSpanRecordsError() {
        Span span = JedisHelper.startSpan(new StubProtocolCommand("SET"));
        Scope scope = span.makeCurrent();

        JedisHelper.endSpan(span, scope, new RuntimeException("READONLY"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void endSpanHandlesNullSpan() {
        JedisHelper.endSpan(null, null, null);
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void endSpanSuccessNoError() {
        Span span = JedisHelper.startSpan(new StubProtocolCommand("DEL"));
        Scope scope = span.makeCurrent();

        JedisHelper.endSpan(span, scope, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).isEmpty();
    }
}
