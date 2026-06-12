package io.last9.tracing.otel.v4.agent;

import io.last9.tracing.otel.v4.BodyCaptureConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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

    // ---- Body capture tests ----

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
    void requestBodyCapturedWhenInputStreamNotMarkSupported() {
        // Simulates Undertow's ServletInputStream (markSupported=false)
        enableBodyCapture(true, false);
        byte[] body = "{\"wsId\":123}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        StubHttpRequest req = new StubHttpRequest("POST", new StubUriInfo("/api/v1/rounds"),
                headers, body, false); // markSupported=false

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body")))
                .isEqualTo("{\"wsId\":123}");
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

    // ---- url.query / url.full tests ----

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
    void urlFullSetFromRequestUri() throws Exception {
        URI requestUri = new URI("https://api.example.com:8443/api/v1/rounds?wsId=123");
        StubHttpRequest req = requestWithUri("GET", "/api/v1/rounds", requestUri,
                Collections.emptyMap());

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("url.full")))
                .isEqualTo("https://api.example.com:8443/api/v1/rounds?wsId=123");
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
        URI absUri = new URI("http://api.example.com/api/v1/rounds?wsId=fallback");
        StubUriInfo uriInfo = new StubUriInfo("/api/v1/rounds", null, absUri);
        StubHttpRequest req = new StubHttpRequest("GET", uriInfo, Collections.emptyMap(), null);

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("url.query")))
                .isEqualTo("wsId=fallback");
    }

    @Test
    void urlFullSetInFallbackPath() throws Exception {
        URI absUri = new URI("http://api.example.com:8080/api/v1/rounds?wsId=fallback");
        StubUriInfo uriInfo = new StubUriInfo("/api/v1/rounds", null, absUri);
        StubHttpRequest req = new StubHttpRequest("GET", uriInfo, Collections.emptyMap(), null);

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("url.full")))
                .isEqualTo("http://api.example.com:8080/api/v1/rounds?wsId=fallback");
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

    // ---- Async exception recording tests ----

    @Test
    void asyncExceptionRecordedViaWriteException() {
        StubHttpRequest req = new StubHttpRequest("POST", "/api/v1/rounds", Collections.emptyMap());
        ResteasyDispatchHelper.startSpan(req);

        RuntimeException asyncError = new RuntimeException("async 500 failure");
        ResteasyDispatchHelper.endSpanFromWriteException(req, new StubHttpResponse(500), asyncError);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e ->
                e.getName().equals("exception") &&
                e.getAttributes().get(AttributeKey.stringKey("exception.message"))
                        .equals("async 500 failure"));
    }

    @Test
    void asyncExceptionNoopWhenNoSpanOnRequest() {
        // Request with no span stored — endSpanFromWriteException must not throw
        StubHttpRequest req = new StubHttpRequest("POST", "/api/v1/rounds", Collections.emptyMap());
        ResteasyDispatchHelper.endSpanFromWriteException(req, new StubHttpResponse(500),
                new RuntimeException("orphan"));
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    // ---- Response body capture tests ----

    @Test
    void responseBodyCapturedWhenEnabled() throws Exception {
        enableBodyCapture(true, false);
        byte[] respBytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = new StubHttpRequest("GET", "/api/v1/data", Collections.emptyMap());
        StubHttpResponse resp = new StubHttpResponse(200, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.captureResponseSetup(req, resp);
        resp.getOutputStream().write(respBytes);
        ResteasyDispatchHelper.endSpan(span, req, resp, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.response.body")))
                .isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void responseBodyNotCapturedWhenDisabled() throws Exception {
        BodyCaptureConfig.envProvider = key -> null;
        byte[] respBytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = new StubHttpRequest("GET", "/api/v1/data", Collections.emptyMap());
        StubHttpResponse resp = new StubHttpResponse(200, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.captureResponseSetup(req, resp);
        resp.getOutputStream().write(respBytes);
        ResteasyDispatchHelper.endSpan(span, req, resp, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.response.body"))).isNull();
    }

    @Test
    void responseBodyNotCapturedFor2xxWhenErrorOnly() throws Exception {
        enableBodyCapture(true, true);
        byte[] respBytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = new StubHttpRequest("GET", "/api/v1/data", Collections.emptyMap());
        StubHttpResponse resp = new StubHttpResponse(200, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.captureResponseSetup(req, resp);
        resp.getOutputStream().write(respBytes);
        ResteasyDispatchHelper.endSpan(span, req, resp, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.response.body"))).isNull();
    }

    @Test
    void responseBodyCapturedFor4xxWhenErrorOnly() throws Exception {
        enableBodyCapture(true, true);
        byte[] respBytes = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = new StubHttpRequest("GET", "/api/v1/data", Collections.emptyMap());
        StubHttpResponse resp = new StubHttpResponse(404, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.captureResponseSetup(req, resp);
        resp.getOutputStream().write(respBytes);
        ResteasyDispatchHelper.endSpan(span, req, resp, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.response.body")))
                .isEqualTo("{\"error\":\"not found\"}");
    }

    @Test
    void responseBodyCapturedFor5xxWhenErrorOnly() throws Exception {
        enableBodyCapture(true, true);
        byte[] respBytes = "{\"error\":\"server fault\"}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = new StubHttpRequest("GET", "/api/v1/data", Collections.emptyMap());
        StubHttpResponse resp = new StubHttpResponse(500, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.captureResponseSetup(req, resp);
        resp.getOutputStream().write(respBytes);
        ResteasyDispatchHelper.endSpan(span, req, resp, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.response.body")))
                .isEqualTo("{\"error\":\"server fault\"}");
    }

    @Test
    void responseBodyNotCapturedForBinaryContentType() throws Exception {
        enableBodyCapture(true, false);
        byte[] respBytes = new byte[]{1, 2, 3};
        StubHttpRequest req = new StubHttpRequest("GET", "/api/v1/file", Collections.emptyMap());
        StubHttpResponse resp = new StubHttpResponse(200, "application/octet-stream");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.captureResponseSetup(req, resp);
        resp.getOutputStream().write(respBytes);
        ResteasyDispatchHelper.endSpan(span, req, resp, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.response.body"))).isNull();
    }

    @Test
    void responseBodyCapturedForAsyncHandler() throws Exception {
        // async path: invoke() closes scope but does NOT end span; asynchronousDelivery ends it
        enableBodyCapture(true, false);
        byte[] respBytes = "{\"async\":true}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = new StubHttpRequest("GET", "/api/v1/data", Collections.emptyMap(),
                null, true, true); // suspended=true → async
        StubHttpResponse resp = new StubHttpResponse(200, "application/json");

        // Simulate invoke() enter
        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.captureResponseSetup(req, resp);

        // Simulate invoke() exit for async request (scope closed, span kept alive)
        ResteasyDispatchHelper.closeScope();
        assertThat(Span.current()).isNotSameAs(span); // scope is closed

        // Simulate async delivery: response written, then endSpanFromAsync called
        resp.getOutputStream().write(respBytes);
        ResteasyDispatchHelper.endSpanFromAsync(req, resp, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.response.body")))
                .isEqualTo("{\"async\":true}");
    }

    @Test
    void requestBodyCapturedForAsyncHandler() throws Exception {
        // request body captured on invoke thread, must survive async thread hop to endSpanFromAsync
        enableBodyCapture(true, false);
        byte[] reqBytes = "{\"teamId\":\"t1\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        StubHttpRequest req = new StubHttpRequest("POST", new StubUriInfo("/api/v1/submit"),
                headers, reqBytes, true, true); // suspended=true → async

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.closeScope();

        // Simulate async delivery (different thread in production — no REQUEST_BODY_HOLDER here)
        ResteasyDispatchHelper.endSpanFromAsync(req, new StubHttpResponse(200, "application/json"), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.request.body")))
                .isEqualTo("{\"teamId\":\"t1\"}");
    }

    @Test
    void asyncExceptionEndedViaWriteException() {
        // async error path: invoke() closes scope; writeException ends span with exception
        StubHttpRequest req = new StubHttpRequest("POST", "/api/v1/fail", Collections.emptyMap(),
                null, true, true); // suspended=true
        StubHttpResponse resp = new StubHttpResponse(500, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.closeScope();
        assertThat(Span.current()).isNotSameAs(span);

        RuntimeException asyncErr = new RuntimeException("simulated async failure");
        ResteasyDispatchHelper.endSpanFromWriteException(req, resp, asyncErr);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e ->
                e.getName().equals("exception") &&
                e.getAttributes().get(AttributeKey.stringKey("exception.message"))
                        .equals("simulated async failure"));
    }

    @Test
    void responseBodyTruncatedAtMaxBytes() throws Exception {
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            if (BodyCaptureConfig.ENV_MAX_BYTES.equals(key)) return "5";
            return null;
        };
        byte[] respBytes = "hello world".getBytes(StandardCharsets.UTF_8); // 11 bytes
        StubHttpRequest req = new StubHttpRequest("GET", "/api/v1/data", Collections.emptyMap());
        StubHttpResponse resp = new StubHttpResponse(200, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.captureResponseSetup(req, resp);
        resp.getOutputStream().write(respBytes);
        ResteasyDispatchHelper.endSpan(span, req, resp, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.response.body")))
                .isEqualTo("hello");
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
        private final boolean markSupported;
        private final boolean suspended;
        private InputStream currentStream;
        private final Map<String, Object> attributes = new HashMap<>();

        StubHttpRequest(String method, String path, Map<String, String> headers) {
            this(method, new StubUriInfo(path), headers, null, true, false);
        }

        StubHttpRequest(String method, StubUriInfo uriInfo, Map<String, String> headers,
                        byte[] body) {
            this(method, uriInfo, headers, body, true, false);
        }

        StubHttpRequest(String method, StubUriInfo uriInfo, Map<String, String> headers,
                        byte[] body, boolean markSupported) {
            this(method, uriInfo, headers, body, markSupported, false);
        }

        StubHttpRequest(String method, String path, Map<String, String> headers,
                        byte[] body, boolean markSupported, boolean suspended) {
            this(method, new StubUriInfo(path), headers, body, markSupported, suspended);
        }

        StubHttpRequest(String method, StubUriInfo uriInfo, Map<String, String> headers,
                        byte[] body, boolean markSupported, boolean suspended) {
            this.httpMethod = method;
            this.uri = uriInfo;
            this.httpHeaders = new StubHttpHeaders(headers);
            this.body = body;
            this.markSupported = markSupported;
            this.suspended = suspended;
            resetStream();
        }

        private void resetStream() {
            byte[] bytes = body != null ? body : new byte[0];
            if (markSupported) {
                currentStream = new ByteArrayInputStream(bytes);
            } else {
                currentStream = new java.io.FilterInputStream(new ByteArrayInputStream(bytes)) {
                    @Override public boolean markSupported() { return false; }
                    @Override public void mark(int readlimit) {}
                    @Override public void reset() {}
                };
            }
        }

        public String getHttpMethod() { return httpMethod; }
        public StubUriInfo getUri() { return uri; }
        public StubHttpHeaders getHttpHeaders() { return httpHeaders; }
        public InputStream getInputStream() { return currentStream; }
        public void setInputStream(InputStream is) { this.currentStream = is; }
        public Object getAttribute(String name) { return attributes.get(name); }
        public void setAttribute(String name, Object value) { attributes.put(name, value); }
        public void removeAttribute(String name) { attributes.remove(name); }
        public StubAsyncContext getAsyncContext() { return new StubAsyncContext(suspended); }
    }

    /** Stub for {@code org.jboss.resteasy.spi.ResteasyAsynchronousContext}. */
    static class StubAsyncContext {
        private final boolean suspended;
        StubAsyncContext(boolean suspended) { this.suspended = suspended; }
        public boolean isSuspended() { return suspended; }
    }

    /** Stub for {@code javax.ws.rs.core.UriInfo}. */
    static class StubUriInfo {
        private final String path;
        private final URI requestUri;
        private final URI absolutePath;
        private final List<Object> matchedResources;

        StubUriInfo(String path) { this(path, null, null, Collections.emptyList()); }
        StubUriInfo(String path, URI requestUri) { this(path, requestUri, null, Collections.emptyList()); }
        StubUriInfo(String path, List<Object> matchedResources) {
            this(path, null, null, matchedResources);
        }

        StubUriInfo(String path, URI requestUri, URI absolutePath) {
            this(path, requestUri, absolutePath, Collections.emptyList());
        }

        StubUriInfo(String path, URI requestUri, URI absolutePath, List<Object> matchedResources) {
            this.path = path;
            this.requestUri = requestUri;
            this.absolutePath = absolutePath;
            this.matchedResources = matchedResources;
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

        public List<Object> getMatchedResources() { return matchedResources; }
    }

    // ---- Route extraction tests (JAX-RS @Path template matching) ----

    /**
     * Fake resource class used by route extraction tests.
     * Simulates a class with multiple POST methods that share the same segment count,
     * which is the case that broke the old segment-count approach.
     */
    @javax.ws.rs.Path("/api/v1/contests")
    static class FakeContestResource {
        @javax.ws.rs.POST @javax.ws.rs.Path("/{id}/submit")
        public javax.ws.rs.core.Response submit() { return null; }

        @javax.ws.rs.POST @javax.ws.rs.Path("/{id}/fail")
        public javax.ws.rs.core.Response fail() { return null; }

        @javax.ws.rs.POST @javax.ws.rs.Path("/{id}/fail-sync")
        public javax.ws.rs.core.Response failSync() { return null; }

        @javax.ws.rs.GET @javax.ws.rs.Path("/{id}")
        public javax.ws.rs.core.Response get() { return null; }
    }

    private static StubHttpRequest requestWithMatchedResource(String method, String path,
                                                               Object resource) {
        StubUriInfo uriInfo = new StubUriInfo(path, Collections.singletonList(resource));
        return new StubHttpRequest(method, uriInfo, Collections.emptyMap(), null);
    }

    @Test
    void routeDisambiguatedForPostToFail() {
        // Regression: all 3 POST methods have 2 segments — old segment-count approach
        // returned the first one found (submit). Template matching must return fail.
        FakeContestResource resource = new FakeContestResource();
        StubHttpRequest req = requestWithMatchedResource("POST",
                "/api/v1/contests/42/fail", resource);

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(500), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.route")))
                .isEqualTo("/api/v1/contests/{id}/fail");
        assertThat(sd.getName()).isEqualTo("POST /api/v1/contests/{id}/fail");
    }

    @Test
    void routeDisambiguatedForPostToFailSync() {
        FakeContestResource resource = new FakeContestResource();
        StubHttpRequest req = requestWithMatchedResource("POST",
                "/api/v1/contests/42/fail-sync", resource);

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(500), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.route")))
                .isEqualTo("/api/v1/contests/{id}/fail-sync");
        assertThat(sd.getName()).isEqualTo("POST /api/v1/contests/{id}/fail-sync");
    }

    @Test
    void routeDisambiguatedForPostToSubmit() {
        FakeContestResource resource = new FakeContestResource();
        StubHttpRequest req = requestWithMatchedResource("POST",
                "/api/v1/contests/42/submit", resource);

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.route")))
                .isEqualTo("/api/v1/contests/{id}/submit");
        assertThat(sd.getName()).isEqualTo("POST /api/v1/contests/{id}/submit");
    }

    @Test
    void routeExtractedForGetWithPathParam() {
        FakeContestResource resource = new FakeContestResource();
        StubHttpRequest req = requestWithMatchedResource("GET",
                "/api/v1/contests/42", resource);

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.endSpan(span, req, new StubHttpResponse(200), null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.route")))
                .isEqualTo("/api/v1/contests/{id}");
        assertThat(sd.getName()).isEqualTo("GET /api/v1/contests/{id}");
    }

    // ---- RESTEasy 4.x async exception flow: writeException → asynchronousDelivery ----

    @Test
    void asyncExceptionRecordedWhenStoredBeforeAsynchronousDelivery() {
        // RESTEasy 4.x: writeException stores exception at onEnter, then calls
        // asynchronousDelivery internally (which ends the span). endSpanFromAsync
        // must read the stored exception even though thrown==null.
        StubHttpRequest req = new StubHttpRequest("POST", "/api/v1/fail",
                Collections.emptyMap(), null, true, true); // suspended
        StubHttpResponse resp = new StubHttpResponse(500, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.closeScope();

        // Simulate writeException.onEnter — stores exception before inner asynchronousDelivery
        RuntimeException asyncErr = new RuntimeException("async failure for contest 42");
        ResteasyDispatchHelper.storeAsyncException(req, asyncErr);

        // Simulate asynchronousDelivery.onExit — thrown=null (the inner call didn't throw)
        ResteasyDispatchHelper.endSpanFromAsync(req, resp, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        assertThat(sd.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(sd.getEvents()).anyMatch(e ->
                e.getName().equals("exception") &&
                e.getAttributes().get(AttributeKey.stringKey("exception.message"))
                        .equals("async failure for contest 42"));
    }

    @Test
    void endSpanFromWriteExceptionIsNoopWhenSpanAlreadyEnded() {
        // After the inner asynchronousDelivery ends the span, the subsequent
        // writeException.onExit call must be a no-op (span.isRecording() == false).
        StubHttpRequest req = new StubHttpRequest("POST", "/api/v1/fail",
                Collections.emptyMap(), null, true, true);
        StubHttpResponse resp = new StubHttpResponse(500, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.closeScope();

        RuntimeException asyncErr = new RuntimeException("async failure");
        ResteasyDispatchHelper.storeAsyncException(req, asyncErr);
        // asynchronousDelivery ends the span
        ResteasyDispatchHelper.endSpanFromAsync(req, resp, null);
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);

        // writeException.onExit fires — must not produce a second span
        ResteasyDispatchHelper.endSpanFromWriteException(req, resp, asyncErr);
        assertThat(spanExporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    void captureResponseSetupIsIdempotent() throws Exception {
        // Second call from asynchronousDelivery.onEnter must not re-wrap the stream.
        enableBodyCapture(true, false);
        byte[] respBytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        StubHttpRequest req = new StubHttpRequest("GET", "/api/v1/data", Collections.emptyMap(),
                null, true, true);
        StubHttpResponse resp = new StubHttpResponse(200, "application/json");

        Span span = ResteasyDispatchHelper.startSpan(req);
        ResteasyDispatchHelper.captureResponseSetup(req, resp); // invoke() enter
        ResteasyDispatchHelper.captureResponseSetup(req, resp); // asynchronousDelivery enter (idempotent)
        ResteasyDispatchHelper.closeScope();

        resp.getOutputStream().write(respBytes);
        ResteasyDispatchHelper.endSpanFromAsync(req, resp, null);

        SpanData sd = spanExporter.getFinishedSpanItems().get(0);
        // Body must appear exactly once (not doubled by double-wrapping)
        assertThat(sd.getAttributes().get(AttributeKey.stringKey("http.response.body")))
                .isEqualTo("{\"status\":\"ok\"}");
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
        private final String contentType;
        private OutputStream outputStream;

        StubHttpResponse(int status) { this(status, null); }

        StubHttpResponse(int status, String contentType) {
            this.status = status;
            this.contentType = contentType;
            this.outputStream = new ByteArrayOutputStream();
        }

        public int getStatus() { return status; }
        public OutputStream getOutputStream() { return outputStream; }
        public void setOutputStream(OutputStream os) { this.outputStream = os; }
        public StubMultivaluedMap getOutputHeaders() {
            Map<String, Object> map = new HashMap<>();
            if (contentType != null) map.put("Content-Type", contentType);
            return new StubMultivaluedMap(map);
        }
    }

    /** Stub for {@code javax.ws.rs.core.MultivaluedMap<String, Object>}. */
    static class StubMultivaluedMap {
        private final Map<String, Object> data;
        StubMultivaluedMap(Map<String, Object> data) { this.data = data; }
        public Object getFirst(Object key) { return data.get(String.valueOf(key)); }
        public List<Object> get(Object key) {
            Object v = data.get(String.valueOf(key));
            return v != null ? Collections.singletonList(v) : null;
        }
    }
}
