package io.last9.tracing.otel.v3.agent;

import io.last9.tracing.otel.v3.BodyCaptureConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
        BodyCaptureConfig.envProvider = key -> null; // disabled by default in tests
    }

    @AfterEach
    void tearDown() {
        otel.tearDown();
        BodyCaptureConfig.envProvider = System::getenv;
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

    // ---- Body capture tests (Bug 1: FDE-196) ----

    @Test
    void requestBodyCapturedOnJsonPostWhen400AndBodyCaptureEnabled() {
        enableBodyCapture(true, true); // ENABLED=true, ERROR_ONLY=true
        byte[] body = "{\"wsId\":123}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = requestWithBody("POST", "/api/v1/rounds", body, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(400), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body")))
                .isEqualTo("{\"wsId\":123}");
    }

    @Test
    void requestBodyNotCapturedWhenBodyCaptureDisabled() {
        BodyCaptureConfig.envProvider = key -> null; // ENABLED=false
        byte[] body = "{\"wsId\":123}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = requestWithBody("POST", "/api/v1/rounds", body, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(400), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body"))).isNull();
    }

    @Test
    void requestBodyNotCapturedFor2xxWhenErrorOnlyEnabled() {
        enableBodyCapture(true, true); // ENABLED=true, ERROR_ONLY=true
        byte[] body = "{\"wsId\":123}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = requestWithBody("POST", "/api/v1/rounds", body, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body"))).isNull();
    }

    @Test
    void requestBodyCapturedFor2xxWhenErrorOnlyDisabled() {
        enableBodyCapture(true, false); // ENABLED=true, ERROR_ONLY=false
        byte[] body = "{\"wsId\":123}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = requestWithBody("POST", "/api/v1/rounds", body, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body")))
                .isEqualTo("{\"wsId\":123}");
    }

    @Test
    void requestBodyNotCapturedForBinaryContentType() {
        enableBodyCapture(true, false);
        byte[] body = new byte[]{1, 2, 3};
        StubHttpRequest req = requestWithBody("POST", "/api/v1/upload", body, "application/octet-stream");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(400), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body"))).isNull();
    }

    @Test
    void requestBodyCapturedFor4xxWhenOnlyErrorOnlySet() {
        // VERTX_OTEL_BODY_CAPTURE_ERROR_ONLY=true alone should enable capture for errors
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_ERROR_ONLY.equals(key) ? "true" : null;
        byte[] body = "{\"wsId\":123}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = requestWithBody("POST", "/api/v1/rounds", body, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(400), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body")))
                .isEqualTo("{\"wsId\":123}");
    }

    @Test
    void requestBodyNotCapturedFor2xxWhenOnlyErrorOnlySet() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_ERROR_ONLY.equals(key) ? "true" : null;
        byte[] body = "{\"wsId\":123}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = requestWithBody("POST", "/api/v1/rounds", body, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body"))).isNull();
    }

    @Test
    void requestBodyCapturedFor5xxWhenErrorOnlyEnabled() {
        enableBodyCapture(true, true);
        byte[] body = "{\"wsId\":123}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = requestWithBody("POST", "/api/v1/rounds", body, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(500), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body")))
                .isEqualTo("{\"wsId\":123}");
    }

    // ---- url.query tests (Bug 3: FDE-196) ----

    @Test
    void urlQueryExtractedFromRequestUri() throws Exception {
        URI requestUri = new URI("http://api.example.com/api/v1/rounds?wsId=123&tournamentId=456");
        StubHttpRequest req = requestWithUri("GET", "/api/v1/rounds", requestUri,
                Collections.emptyMap());

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("url.query")))
                .isEqualTo("wsId=123&tournamentId=456");
    }

    @Test
    void urlSchemeAndHostExtractedFromRequestUri() throws Exception {
        URI requestUri = new URI("https://api.example.com:8443/api/v1/rounds");
        StubHttpRequest req = requestWithUri("GET", "/api/v1/rounds", requestUri,
                Collections.emptyMap());

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("url.scheme"))).isEqualTo("https");
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("server.address")))
                .isEqualTo("api.example.com");
        assertThat(sd.getAttributes().get(AttributeKey.longKey("server.port"))).isEqualTo(8443L);
    }

    @Test
    void urlQueryFallsBackToAbsolutePathWhenGetRequestUriFails() throws Exception {
        // getRequestUri() throws (not configured) → should fall back to getAbsolutePath()
        URI absUri = new URI("http://api.example.com/api/v1/rounds?wsId=fallback");
        StubUriInfo uriInfo = new StubUriInfo("/api/v1/rounds", null, absUri); // requestUri=null → throws
        StubHttpRequest req = new StubHttpRequest("GET", uriInfo, Collections.emptyMap(), null);

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("url.query")))
                .isEqualTo("wsId=fallback");
    }

    @Test
    void bodyAttachedWhenNullResponseAndThrownWithErrorOnly() {
        // response=null, thrown!=null, errorOnly=true → should capture body (it's an error)
        enableBodyCapture(true, true);
        byte[] body = "{\"wsId\":123}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = requestWithBody("POST", "/api/v1/rounds", body, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, null, new RuntimeException("async error"));

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body")))
                .isEqualTo("{\"wsId\":123}");
    }

    // ---- Async exception recording tests (Bug 2: FDE-196) ----

    @Test
    void asyncExceptionRecordedOnCurrentSpan() {
        Span span = ResteasyDispatchHelper.startSpan(
                new StubHttpRequest("POST", "/api/v1/rounds", Collections.emptyMap()));

        RuntimeException asyncError = new RuntimeException("async 500 failure");
        ResteasyDispatchHelper.recordAsyncException(asyncError);

        ResteasyDispatchHelper.endSpan(span, null, new StubHttpResponse(500), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e ->
                e.getName().equals("exception") &&
                e.getAttributes().get(AttributeKey.stringKey("exception.message"))
                        .equals("async 500 failure"));
    }

    @Test
    void asyncExceptionNoopWhenNoCurrentSpan() {
        // Should not throw even with no active span
        ResteasyDispatchHelper.recordAsyncException(new RuntimeException("orphan"));
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    // ---- Test helpers ----

    private static void enableBodyCapture(boolean enabled, boolean errorOnly) {
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return enabled ? "true" : null;
            if (BodyCaptureConfig.ENV_ERROR_ONLY.equals(key)) return errorOnly ? "true" : null;
            return null;
        };
    }

    private static StubHttpRequest requestWithBody(String method, String path,
                                                    byte[] body, String contentType) {
        Map<String, String> headers = new HashMap<>();
        if (contentType != null) headers.put("Content-Type", contentType);
        return new StubHttpRequest(method, new StubUriInfo(path), headers, body);
    }

    private static StubHttpRequest requestWithUri(String method, String path, URI requestUri,
                                                   Map<String, String> headers) {
        return new StubHttpRequest(method, new StubUriInfo(path, requestUri), headers, null);
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
        private final byte[] body;

        StubHttpRequest(String method, String path, Map<String, String> headers) {
            this(method, new StubUriInfo(path), headers, null);
        }

        StubHttpRequest(String method, StubUriInfo uriInfo, Map<String, String> headers,
                        byte[] body) {
            this.httpMethod = method;
            this.uri = uriInfo;
            this.httpHeaders = new StubHttpHeaders(headers);
            this.body = body;
        }

        public String getHttpMethod() { return httpMethod; }
        public StubUriInfo getUri() { return uri; }
        public StubHttpHeaders getHttpHeaders() { return httpHeaders; }
        public InputStream getInputStream() {
            return new ByteArrayInputStream(body != null ? body : new byte[0]);
        }
    }

    /** Stub for {@code javax.ws.rs.core.UriInfo}. */
    static class StubUriInfo {
        private final String path;
        private final URI requestUri;
        private final URI absolutePath;

        StubUriInfo(String path) { this(path, null, null); }
        StubUriInfo(String path, URI requestUri) { this(path, requestUri, null); }

        StubUriInfo(String path, URI requestUri, URI absolutePath) {
            this.path = path;
            this.requestUri = requestUri;
            this.absolutePath = absolutePath;
        }

        public String getPath() { return path; }

        public URI getRequestUri() throws Exception {
            if (requestUri == null) throw new Exception("not configured");
            return requestUri;
        }

        public URI getAbsolutePath() throws Exception {
            if (absolutePath == null) throw new Exception("not configured");
            return absolutePath;
        }
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
