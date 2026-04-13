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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NettyHttpClientHelperTest {

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

    // --- Stub classes that expose methods the helper calls via reflection ---

    /**
     * Stub HTTP request with method(), getHost(), uri(), getPort(), putHeader(),
     * and headers() — the latter is used by hasHeader() to detect pre-injected
     * traceparent headers.
     */
    @SuppressWarnings("unused")
    static class StubHttpRequest {
        private final String httpMethod;
        private final String host;
        private final String uri;
        private final int port;
        final Map<String, String> headers = new HashMap<>();

        StubHttpRequest(String httpMethod, String host, String uri, int port) {
            this.httpMethod = httpMethod;
            this.host = host;
            this.uri = uri;
            this.port = port;
        }

        public Object method() { return httpMethod; }
        public String getHost() { return host; }
        public String uri() { return uri; }
        public int getPort() { return port; }
        public void putHeader(String key, String value) { headers.put(key, value); }
        /** Mirrors HttpClientRequest.headers() — returns the mutable header map. */
        public Map<String, String> headers() { return headers; }
    }

    /**
     * Stub that mimics Vert.x when host is set via HttpClientOptions (not per-request).
     * getHost() returns null, but host() (on the base class) returns the actual host.
     */
    @SuppressWarnings("unused")
    static class StubHttpRequestNullGetHost {
        final Map<String, String> headers = new HashMap<>();
        public Object method() { return "GET"; }
        public String getHost() { return null; }
        public String host() { return "vault.internal"; }
        public String uri() { return "/v1/secrets/data/myapp"; }
        public int getPort() { return 8200; }
        public void putHeader(String key, String value) { headers.put(key, value); }
        public Map<String, String> headers() { return headers; }
    }

    /** Stub HTTP request WITHOUT putHeader — tests silent failure of injection. */
    @SuppressWarnings("unused")
    static class StubHttpRequestNoPutHeader {
        final Map<String, String> headers = new HashMap<>();
        public Object method() { return "GET"; }
        public String getHost() { return "host"; }
        public String uri() { return "/path"; }
        public int getPort() { return 80; }
        public Map<String, String> headers() { return headers; }
    }

    /** Stub HTTP response with statusCode(). */
    @SuppressWarnings("unused")
    static class StubHttpResponse {
        private final int code;
        StubHttpResponse(int code) { this.code = code; }
        public Integer statusCode() { return code; }
    }

    // --- startSpan tests ---

    @Test
    void startSpanCreatesClientSpanWithHttpAttributes() {
        StubHttpRequest request = new StubHttpRequest("GET", "api.example.com", "/v1/users", 443);

        Span span = NettyHttpClientHelper.startSpan(request);
        assertThat(span).isNotNull();

        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);
        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getName()).isEqualTo("GET api.example.com");
        assertThat(sd.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.method")))
                .isEqualTo("GET");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("net.peer.name")))
                .isEqualTo("api.example.com");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.url")))
                .isEqualTo("https://api.example.com/v1/users");
    }

    @Test
    void startSpanInjectsTraceparentHeader() {
        StubHttpRequest request = new StubHttpRequest("POST", "svc.local", "/api", 8080);

        Span span = NettyHttpClientHelper.startSpan(request);
        assertThat(span).isNotNull();

        assertThat(request.headers).containsKey("traceparent");
        String traceparent = request.headers.get("traceparent");
        assertThat(traceparent).startsWith("00-");
        assertThat(traceparent.split("-")).hasSize(4);

        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);
        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));
    }

    /**
     * Regression test: when TracedWebClient already injected a {@code traceparent} header
     * (at assembly time, before end() fires), the Netty advice must NOT create a duplicate
     * CLIENT span.
     *
     * <p>Old approach used an {@code IN_HTTP_CLIENT_CALL} ThreadLocal guard that had a timing
     * problem — the flag was reset before end() was called. New approach checks for the
     * {@code traceparent} header directly on the request object, which is set at assembly
     * time and persists until end() fires. This test fails on the old code and passes on
     * the new code.
     */
    @Test
    void startSpanReturnsNullWhenTraceparentAlreadyPresent() {
        StubHttpRequest request = new StubHttpRequest("GET", "pricing-service", "/v1/price/AAPL", 8081);

        // Simulate TracedWebClient injecting traceparent into the request headers
        // at assembly time (before end() fires). This is what TracedHttpRequest.wrapWithSpan()
        // does via delegate.putHeader("traceparent", ...).
        request.headers.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        // Netty advice fires at end() — should detect the pre-existing traceparent and skip.
        Span span = NettyHttpClientHelper.startSpan(request);

        assertThat(span)
                .as("startSpan must return null when traceparent is already on the request")
                .isNull();
        assertThat(spanExporter.getFinishedSpanItems())
                .as("no CLIENT span should be created when TracedWebClient already handles it")
                .isEmpty();
    }

    @Test
    void startSpanCreatesSpanWhenNoTraceparentPresent() {
        // Raw HttpClient call (no TracedWebClient) — no pre-injected traceparent
        StubHttpRequest request = new StubHttpRequest("GET", "external.api", "/data", 443);

        Span span = NettyHttpClientHelper.startSpan(request);

        assertThat(span)
                .as("startSpan must create a CLIENT span when no traceparent is present")
                .isNotNull();

        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);
        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void startSpanSetsPortAttributeForNonDefaultPort() {
        StubHttpRequest request = new StubHttpRequest("GET", "host", "/path", 9090);

        Span span = NettyHttpClientHelper.startSpan(request);
        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);
        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.longKey("net.peer.port")))
                .isEqualTo(9090L);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.url")))
                .isEqualTo("http://host:9090/path");
    }

    @Test
    void startSpanBuildsHttpsUrlForPort443() {
        StubHttpRequest request = new StubHttpRequest("GET", "secure.api", "/data", 443);

        Span span = NettyHttpClientHelper.startSpan(request);
        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);
        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.url")))
                .isEqualTo("https://secure.api/data");
    }

    @Test
    void startSpanDoesNotCrashWhenPutHeaderAbsent() {
        StubHttpRequestNoPutHeader request = new StubHttpRequestNoPutHeader();

        Span span = NettyHttpClientHelper.startSpan(request);
        assertThat(span).isNotNull();

        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);
        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void startSpanFallsBackToHostMethodWhenGetHostReturnsNull() {
        StubHttpRequestNullGetHost request = new StubHttpRequestNullGetHost();

        Span span = NettyHttpClientHelper.startSpan(request);
        assertThat(span).isNotNull();

        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);
        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getName()).isEqualTo("GET vault.internal");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("net.peer.name")))
                .isEqualTo("vault.internal");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.url")))
                .isEqualTo("http://vault.internal:8200/v1/secrets/data/myapp");
    }

    // --- handleResponse tests ---

    @Test
    void handleResponseSetsStatusCodeAndEndsSpan() {
        StubHttpRequest request = new StubHttpRequest("GET", "host", "/ok", 80);
        Span span = NettyHttpClientHelper.startSpan(request);
        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);

        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getAttributes().get(AttributeKey.longKey("http.status_code")))
                .isEqualTo(200L);
        assertThat(sd.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }

    @Test
    void handleResponseSetsErrorStatusFor5xx() {
        StubHttpRequest request = new StubHttpRequest("POST", "host", "/fail", 80);
        Span span = NettyHttpClientHelper.startSpan(request);
        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);

        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(503));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.longKey("http.status_code")))
                .isEqualTo(503L);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getStatus().getDescription()).contains("503");
    }

    @Test
    void handleResponseSetsErrorStatusFor4xx() {
        StubHttpRequest request = new StubHttpRequest("GET", "host", "/missing", 80);
        Span span = NettyHttpClientHelper.startSpan(request);
        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);

        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(404));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.longKey("http.status_code")))
                .isEqualTo(404L);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void handleResponseNoOpForUnknownRequest() {
        StubHttpRequest unknown = new StubHttpRequest("GET", "host", "/", 80);
        NettyHttpClientHelper.handleResponse(unknown, new StubHttpResponse(200));

        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    // --- handleException tests ---

    @Test
    void handleExceptionRecordsErrorAndEndsSpan() {
        StubHttpRequest request = new StubHttpRequest("GET", "host", "/timeout", 80);
        Span span = NettyHttpClientHelper.startSpan(request);
        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);

        NettyHttpClientHelper.handleException(request, new RuntimeException("Connection refused"));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getStatus().getDescription()).contains("Connection refused");
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void handleExceptionNoOpForUnknownRequest() {
        StubHttpRequest unknown = new StubHttpRequest("GET", "host", "/", 80);
        NettyHttpClientHelper.handleException(unknown, new RuntimeException("oops"));

        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    // --- exitSend tests ---

    @Test
    void exitSendDoesNotEndSpanOnSuccess() {
        StubHttpRequest request = new StubHttpRequest("GET", "host", "/", 80);
        Span span = NettyHttpClientHelper.startSpan(request);

        Scope scope = span.makeCurrent();
        NettyHttpClientHelper.exitSend(request, span, scope, null);

        // Span is NOT ended by exitSend — it ends when the response arrives
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();

        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void exitSendEndsSpanAndCleansUpInFlightWhenEndThrows() {
        StubHttpRequest request = new StubHttpRequest("GET", "host", "/", 80);
        Span span = NettyHttpClientHelper.startSpan(request);
        Scope scope = span.makeCurrent();

        NettyHttpClientHelper.exitSend(request, span, scope, new RuntimeException("write error"));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);

        // handleResponse is a no-op now — entry was cleaned from IN_FLIGHT
        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    // --- Full lifecycle tests ---

    @Test
    void fullLifecycleRequestToResponse() {
        StubHttpRequest request = new StubHttpRequest("POST", "payment.svc", "/charge", 8443);

        Span span = NettyHttpClientHelper.startSpan(request);
        assertThat(span).isNotNull();

        Scope scope = span.makeCurrent();
        NettyHttpClientHelper.exitSend(request, span, scope, null);

        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();

        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(201));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getName()).isEqualTo("POST payment.svc");
        assertThat(sd.getAttributes().get(AttributeKey.longKey("http.status_code")))
                .isEqualTo(201L);
    }

    @Test
    void fullLifecycleRequestToException() {
        StubHttpRequest request = new StubHttpRequest("GET", "flaky.svc", "/data", 80);

        Span span = NettyHttpClientHelper.startSpan(request);
        Scope scope = span.makeCurrent();
        NettyHttpClientHelper.exitSend(request, span, scope, null);

        NettyHttpClientHelper.handleException(request,
                new java.net.ConnectException("Connection timed out"));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData sd = spans.get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void multipleInFlightRequestsTrackedIndependently() {
        StubHttpRequest req1 = new StubHttpRequest("GET", "svc-a", "/a", 80);
        StubHttpRequest req2 = new StubHttpRequest("POST", "svc-b", "/b", 80);

        Span span1 = NettyHttpClientHelper.startSpan(req1);
        NettyHttpClientHelper.exitSend(req1, span1, span1.makeCurrent(), null);

        Span span2 = NettyHttpClientHelper.startSpan(req2);
        NettyHttpClientHelper.exitSend(req2, span2, span2.makeCurrent(), null);

        // Response for req2 arrives first
        NettyHttpClientHelper.handleResponse(req2, new StubHttpResponse(200));
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
        assertThat(spanExporter.getFinishedSpanItems().get(0).getName())
                .isEqualTo("POST svc-b");

        // Response for req1 arrives second
        NettyHttpClientHelper.handleResponse(req1, new StubHttpResponse(500));
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(2);

        List<String> names = new ArrayList<>();
        for (SpanData sd : spanExporter.getFinishedSpanItems()) {
            names.add(sd.getName());
        }
        assertThat(names).containsExactlyInAnyOrder("GET svc-a", "POST svc-b");
    }

    @Test
    void duplicateResponseCallIsNoOp() {
        StubHttpRequest request = new StubHttpRequest("GET", "host", "/", 80);
        Span span = NettyHttpClientHelper.startSpan(request);
        NettyHttpClientHelper.exitSend(request, span, span.makeCurrent(), null);

        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);

        NettyHttpClientHelper.handleResponse(request, new StubHttpResponse(200));
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }
}
