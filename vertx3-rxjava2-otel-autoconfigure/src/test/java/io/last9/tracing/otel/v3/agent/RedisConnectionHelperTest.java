package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisConnectionHelperTest {

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

    @Test
    void startSpanCreatesClientSpanForGetCommand() {
        Request request = Request.cmd(Command.GET).arg("session:abc");
        Span span = RedisConnectionHelper.startSpan(request);

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
        Request request = Request.cmd(Command.SET).arg("key").arg("value");
        Span span = RedisConnectionHelper.startSpan(request);

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("redis SET");
    }

    @Test
    void startSpanCreatesSpanForHgetallCommand() {
        Request request = Request.cmd(Command.HGETALL).arg("user:42");
        Span span = RedisConnectionHelper.startSpan(request);

        assertThat(span).isNotNull();
        span.end();

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("redis HGETALL");
    }

    @Test
    void startSpanReturnsNullWhenGuardIsSet() {
        AgentGuard.IN_DB_TRACED_CALL.set(true);

        Request request = Request.cmd(Command.GET).arg("key");
        Span span = RedisConnectionHelper.startSpan(request);

        assertThat(span).isNull();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void endSpanRecordsError() {
        Request request = Request.cmd(Command.GET).arg("key");
        Span span = RedisConnectionHelper.startSpan(request);
        Scope scope = span.makeCurrent();

        RedisConnectionHelper.endSpan(span, scope, new RuntimeException("connection reset"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void endSpanHandlesNullSpan() {
        RedisConnectionHelper.endSpan(null, null, null);
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void endSpanSuccessNoError() {
        Request request = Request.cmd(Command.DEL).arg("expired-key");
        Span span = RedisConnectionHelper.startSpan(request);
        Scope scope = span.makeCurrent();

        RedisConnectionHelper.endSpan(span, scope, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).isEmpty();
    }
}
