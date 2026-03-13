package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResteasyDispatchHelperTest {

    private GlobalOtelTestSetup otel;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void setUp() {
        otel = new GlobalOtelTestSetup();
        otel.setUp();
        spanExporter = otel.getSpanExporter();
    }

    @AfterEach
    void tearDown() {
        otel.tearDown();
    }

    @Test
    void startSpanCreatesServerSpanForGetRequest() {
        StubHttpRequest request = new StubHttpRequest("GET", "/api/v1/contests",
                Collections.emptyMap());

        Span span = ResteasyDispatchHelper.startSpan(request);
        assertThat(span).isNotNull();
        ResteasyDispatchHelper.endSpan(span, null, new StubHttpResponse(200), null);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getName()).isEqualTo("GET /api/v1/contests");
        assertThat(sd.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.method")))
                .isEqualTo("GET");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("url.path")))
                .isEqualTo("/api/v1/contests");
    }

    @Test
    void startSpanCreatesSpanForPostRequest() {
        Span span = ResteasyDispatchHelper.startSpan(
                new StubHttpRequest("POST", "/api/v1/teams", Collections.emptyMap()));
        ResteasyDispatchHelper.endSpan(span, null, new StubHttpResponse(201), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("POST /api/v1/teams");
    }

    @Test
    void endSpanRecordsResponseStatus() {
        Span span = ResteasyDispatchHelper.startSpan(
                new StubHttpRequest("GET", "/api/health", Collections.emptyMap()));
        ResteasyDispatchHelper.endSpan(span, null, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.longKey("http.response.status_code")))
                .isEqualTo(200L);
    }

    @Test
    void endSpanSetsErrorStatusForServerError() {
        Span span = ResteasyDispatchHelper.startSpan(
                new StubHttpRequest("GET", "/api/fail", Collections.emptyMap()));
        ResteasyDispatchHelper.endSpan(span, null, new StubHttpResponse(500), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void endSpanRecordsException() {
        Span span = ResteasyDispatchHelper.startSpan(
                new StubHttpRequest("GET", "/api/crash", Collections.emptyMap()));
        RuntimeException error = new RuntimeException("null pointer");
        ResteasyDispatchHelper.endSpan(span, null, new StubHttpResponse(500), error);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void spanContextIsMadeCurrent() {
        Span span = ResteasyDispatchHelper.startSpan(
                new StubHttpRequest("GET", "/api/test", Collections.emptyMap()));

        // Between startSpan and endSpan, the span should be current
        assertThat(Span.current()).isSameAs(span);

        ResteasyDispatchHelper.endSpan(span, null, new StubHttpResponse(200), null);

        // After endSpan, the scope should be closed
        assertThat(Span.current()).isNotSameAs(span);
    }

    @Test
    void traceparentExtractedFromHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        Span span = ResteasyDispatchHelper.startSpan(
                new StubHttpRequest("GET", "/api/traced", headers));
        ResteasyDispatchHelper.endSpan(span, null, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getParentSpanId()).isEqualTo("b7ad6b7169203331");
        assertThat(sd.getSpanContext().getTraceId())
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void startSpanHandlesNullMethodGracefully() {
        Span span = ResteasyDispatchHelper.startSpan(
                new StubHttpRequest(null, "/api/test", Collections.emptyMap()));
        assertThat(span).isNotNull();
        ResteasyDispatchHelper.endSpan(span, null, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("UNKNOWN /api/test");
    }

    @Test
    void endSpanHandlesNullSpan() {
        ResteasyDispatchHelper.endSpan(null, null, new StubHttpResponse(200), null);
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void endSpanHandlesNullResponse() {
        Span span = ResteasyDispatchHelper.startSpan(
                new StubHttpRequest("GET", "/api/test", Collections.emptyMap()));
        ResteasyDispatchHelper.endSpan(span, null, null, null);

        // Span should still be created and ended, just without status code
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    // ---- Stubs (reflection-compatible stand-ins for RESTEasy types) ----

    /**
     * Stub for {@code org.jboss.resteasy.spi.HttpRequest}. Has the same method
     * signatures so that {@link ResteasyDispatchHelper}'s reflection calls work.
     */
    static class StubHttpRequest {
        private final String httpMethod;
        private final StubUriInfo uri;
        private final StubHttpHeaders httpHeaders;

        StubHttpRequest(String method, String path, Map<String, String> headers) {
            this.httpMethod = method;
            this.uri = new StubUriInfo(path);
            this.httpHeaders = new StubHttpHeaders(headers);
        }

        public String getHttpMethod() { return httpMethod; }
        public StubUriInfo getUri() { return uri; }
        public StubHttpHeaders getHttpHeaders() { return httpHeaders; }
    }

    /** Stub for {@code javax.ws.rs.core.UriInfo}. */
    static class StubUriInfo {
        private final String path;
        StubUriInfo(String path) { this.path = path; }
        public String getPath() { return path; }
    }

    /** Stub for {@code javax.ws.rs.core.HttpHeaders}. */
    static class StubHttpHeaders {
        private final Map<String, String> headers;
        StubHttpHeaders(Map<String, String> headers) { this.headers = headers; }

        public String getHeaderString(String name) { return headers.get(name); }

        public Map<String, List<String>> getRequestHeaders() {
            Map<String, List<String>> map = new HashMap<>();
            headers.forEach((k, v) -> map.put(k, Collections.singletonList(v)));
            return map;
        }
    }

    /** Stub for {@code org.jboss.resteasy.spi.HttpResponse}. */
    static class StubHttpResponse {
        private final int status;
        StubHttpResponse(int status) { this.status = status; }
        public int getStatus() { return status; }
    }
}
