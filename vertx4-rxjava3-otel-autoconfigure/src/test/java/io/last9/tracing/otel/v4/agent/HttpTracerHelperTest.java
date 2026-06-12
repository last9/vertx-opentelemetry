package io.last9.tracing.otel.v4.agent;

import io.last9.tracing.otel.v4.TestOtelSetup;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.http.HttpClientResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class HttpTracerHelperTest {

    private TestOtelSetup otel;

    @BeforeEach
    void setUp() {
        otel = new TestOtelSetup();
    }

    @AfterEach
    void tearDown() {
        otel.shutdown();
    }

    @Test
    void enrichOnReceiveRecordsExceptionWhenFailurePresent() {
        Span span = otel.getTracer().spanBuilder("GET /test").startSpan();
        StubOperation operation = new StubOperation(span);

        HttpTracerHelper.enrichOnReceive(
                null,
                operation,
                new RuntimeException("connection reset"));

        span.end();

        SpanData data = otel.getSpanExporter().getFinishedSpanItems().get(0);
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getEvents())
                .anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void enrichOnReceiveRecordsExceptionStacktraceWhenFailurePresent() {
        Span span = otel.getTracer().spanBuilder("GET /test").startSpan();
        StubOperation operation = new StubOperation(span);

        HttpTracerHelper.enrichOnReceive(
                null,
                operation,
                new RuntimeException("connection reset"));

        span.end();

        EventData event = otel.getSpanExporter().getFinishedSpanItems().get(0).getEvents().stream()
                .filter(e -> e.getName().equals("exception"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected exception event"));
        assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.type")))
                .isEqualTo("java.lang.RuntimeException");
        assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.stacktrace")))
                .isNotBlank()
                .contains("connection reset");
    }

    @Test
    void enrichOnReceiveDoesNotRecordExceptionForHttpStatusOnly() {
        Span span = otel.getTracer().spanBuilder("GET /test").startSpan();
        StubOperation operation = new StubOperation(span);

        HttpTracerHelper.enrichOnReceive(
                httpResponse(503),
                operation,
                null);

        span.end();

        SpanData data = otel.getSpanExporter().getFinishedSpanItems().get(0);
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getEvents())
                .noneMatch(e -> e.getName().equals("exception"));
    }

    private static final class StubOperation {
        private final Span span;

        StubOperation(Span span) {
            this.span = span;
        }

        @SuppressWarnings("unused")
        public Span span() {
            return span;
        }
    }

    private static HttpClientResponse httpResponse(int statusCode) {
        return (HttpClientResponse) Proxy.newProxyInstance(
                HttpClientResponse.class.getClassLoader(),
                new Class<?>[] {HttpClientResponse.class},
                (proxy, method, args) -> {
                    if ("statusCode".equals(method.getName())) {
                        return statusCode;
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    return null;
                });
    }
}
