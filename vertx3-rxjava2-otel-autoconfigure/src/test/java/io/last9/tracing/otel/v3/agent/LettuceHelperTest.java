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

class LettuceHelperTest {

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
     * Stub for Lettuce's CommandType enum — returned by RedisCommand.getType().
     * Exposes name() like a Java enum.
     */
    @SuppressWarnings("unused")
    static class StubCommandType {
        private final String name;
        StubCommandType(String name) { this.name = name; }
        public String name() { return name; }
    }

    /**
     * Stub for Lettuce's RedisCommand — has getType() returning a ProtocolKeyword.
     */
    @SuppressWarnings("unused")
    static class StubRedisCommand {
        private final StubCommandType type;
        StubRedisCommand(String commandName) { this.type = new StubCommandType(commandName); }
        public StubCommandType getType() { return type; }
    }

    /**
     * Stub RedisCommand where getType() returns null — tests fallback.
     */
    @SuppressWarnings("unused")
    static class StubRedisCommandNullType {
        public Object getType() { return null; }
    }

    @Test
    void startSpanCreatesClientSpanForGetCommand() {
        Span span = LettuceHelper.startSpan(new StubRedisCommand("GET"));

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
    void startSpanCreatesSpanForHsetCommand() {
        Span span = LettuceHelper.startSpan(new StubRedisCommand("HSET"));
        assertThat(span).isNotNull();
        span.end();

        assertThat(spanExporter.getFinishedSpanItems().get(0).getName())
                .isEqualTo("redis HSET");
    }

    @Test
    void startSpanReturnsNullWhenGuardIsSet() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);

        Span span = LettuceHelper.startSpan(new StubRedisCommand("GET"));

        assertThat(span).isNull();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void startSpanHandlesNullCommand() {
        Span span = LettuceHelper.startSpan(null);
        assertThat(span).isNotNull();
        span.end();

        assertThat(spanExporter.getFinishedSpanItems().get(0).getName())
                .isEqualTo("redis UNKNOWN");
    }

    @Test
    void startSpanHandlesNullCommandType() {
        Span span = LettuceHelper.startSpan(new StubRedisCommandNullType());
        assertThat(span).isNotNull();
        span.end();

        assertThat(spanExporter.getFinishedSpanItems().get(0).getName())
                .isEqualTo("redis UNKNOWN");
    }

    @Test
    void endSpanRecordsError() {
        Span span = LettuceHelper.startSpan(new StubRedisCommand("SET"));
        Scope scope = span.makeCurrent();

        LettuceHelper.endSpan(span, scope, new RuntimeException("MOVED 3999"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void endSpanHandlesNullSpan() {
        LettuceHelper.endSpan(null, null, null);
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void endSpanSuccessNoError() {
        Span span = LettuceHelper.startSpan(new StubRedisCommand("PING"));
        Scope scope = span.makeCurrent();

        LettuceHelper.endSpan(span, scope, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).isEmpty();
    }
}
