# HTTP Body Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture HTTP request/response bodies as OTel span attributes (`http.request.body`, `http.response.body`) with zero user code changes for v3 agent users; library-path request body for v4 users.

**Architecture:** Extend `NettyServerTracingHandler` (v3 agent) to accumulate `HttpContent` bytes via Netty channel attributes, set span attributes in `endServerSpan()`. v4 library path reads `ctx.getBody()` in `SpanNameUpdater`. All options configurable via env vars in `BodyCaptureConfig`.

**Tech Stack:** Java 17, Netty 4.x (`EmbeddedChannel` for unit tests), Vert.x 3.9.16 / 4.5.10, OTel SDK 1.38.0, JUnit 5, AssertJ, ByteBuddy (no new advice classes — extends existing Netty handler).

---

## Scope note — v4 agent path

v4 agent body capture is deferred. The VertxTracer SPI creates SERVER spans after Netty decoding, so `Span.current()` is not the SERVER span when `HttpRequest` arrives in the Netty handler. v4 gets request body capture via `SpanNameUpdater` (library path, requires `BodyHandler`). Response body not supported in v4. See open question #1 in the spec.

---

## File map

| File | Action |
|---|---|
| `vertx3-rxjava2-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v3/BodyCaptureConfig.java` | Rewrite |
| `vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/BodyCaptureConfigTest.java` | Create |
| `vertx3-rxjava2-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v3/agent/NettyServerTracingHandler.java` | Modify — add body accumulation |
| `vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/agent/NettyServerTracingHandlerBodyTest.java` | Create |
| `vertx3-rxjava2-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v3/TracedRouter.java` | Modify — update body capture to new config |
| `vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/TracedRouterBodyCaptureTest.java` | Create |
| `vertx4-rxjava3-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v4/BodyCaptureConfig.java` | Rewrite |
| `vertx4-rxjava3-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v4/BodyCaptureConfigTest.java` | Create |
| `vertx4-rxjava3-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v4/SpanNameUpdater.java` | Modify — remove duplicates, add body capture |
| `vertx4-rxjava3-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v4/SpanNameUpdaterBodyCaptureTest.java` | Create |

---

## Task 1: Rewrite BodyCaptureConfig (v3)

**Files:**
- Rewrite: `vertx3-rxjava2-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v3/BodyCaptureConfig.java`
- Create: `vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/BodyCaptureConfigTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/BodyCaptureConfigTest.java
package io.last9.tracing.otel.v3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BodyCaptureConfigTest {

    @AfterEach
    void resetEnv() {
        BodyCaptureConfig.envProvider = System::getenv;
    }

    @Test
    void enabledFalseByDefault() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.enabled()).isFalse();
    }

    @Test
    void enabledViaPrimaryEnvVar() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_ENABLED.equals(key) ? "true" : null;
        assertThat(BodyCaptureConfig.enabled()).isTrue();
    }

    @Test
    void enabledViaLegacyEnvVar() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_LEGACY.equals(key) ? "1" : null;
        assertThat(BodyCaptureConfig.enabled()).isTrue();
    }

    @Test
    void captureRequestDefaultTrue() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.captureRequest()).isTrue();
    }

    @Test
    void captureResponseDefaultTrue() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.captureResponse()).isTrue();
    }

    @Test
    void maxBytesDefault8192() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.maxBytes()).isEqualTo(8192);
    }

    @Test
    void maxBytesCustom() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_MAX_BYTES.equals(key) ? "4096" : null;
        assertThat(BodyCaptureConfig.maxBytes()).isEqualTo(4096);
    }

    @Test
    void errorOnlyDefaultFalse() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.errorOnly()).isFalse();
    }

    @Test
    void isAllowedContentTypeJson() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedContentType("application/json")).isTrue();
        assertThat(BodyCaptureConfig.isAllowedContentType("application/json; charset=utf-8")).isTrue();
    }

    @Test
    void isAllowedContentTypeXml() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedContentType("application/xml")).isTrue();
    }

    @Test
    void isAllowedContentTypeText() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedContentType("text/plain")).isTrue();
        assertThat(BodyCaptureConfig.isAllowedContentType("text/html")).isTrue();
    }

    @Test
    void isAllowedContentTypeRejectsFormData() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedContentType("multipart/form-data")).isFalse();
        assertThat(BodyCaptureConfig.isAllowedContentType("application/octet-stream")).isFalse();
    }

    @Test
    void isAllowedPathNoFilters() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedPath("/api/v1/users")).isTrue();
        assertThat(BodyCaptureConfig.isAllowedPath("/health")).isTrue();
    }

    @Test
    void isAllowedPathExclude() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_EXCLUDE_PATHS.equals(key) ? "/health,/metrics" : null;
        assertThat(BodyCaptureConfig.isAllowedPath("/health")).isFalse();
        assertThat(BodyCaptureConfig.isAllowedPath("/metrics")).isFalse();
        assertThat(BodyCaptureConfig.isAllowedPath("/api/v1/users")).isTrue();
    }

    @Test
    void isAllowedPathInclude() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_INCLUDE_PATHS.equals(key) ? "/api" : null;
        assertThat(BodyCaptureConfig.isAllowedPath("/api/v1/users")).isTrue();
        assertThat(BodyCaptureConfig.isAllowedPath("/health")).isFalse();
    }

    @Test
    void contentTypesCustomList() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_CONTENT_TYPES.equals(key) ? "text/plain,application/json" : null;
        assertThat(BodyCaptureConfig.isAllowedContentType("text/plain")).isTrue();
        assertThat(BodyCaptureConfig.isAllowedContentType("application/xml")).isFalse();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl vertx3-rxjava2-otel-autoconfigure -Dtest=BodyCaptureConfigTest --no-transfer-progress 2>&1 | tail -20
```

Expected: compilation errors — `envProvider` field and new methods don't exist yet.

- [ ] **Step 3: Rewrite BodyCaptureConfig**

Replace the entire file:

```java
package io.last9.tracing.otel.v3;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

public final class BodyCaptureConfig {

    public static final String ENV_LEGACY        = "VERTX_OTEL_CAPTURE_HTTP_BODY";
    public static final String ENV_ENABLED       = "VERTX_OTEL_BODY_CAPTURE_ENABLED";
    public static final String ENV_REQUEST       = "VERTX_OTEL_BODY_CAPTURE_REQUEST";
    public static final String ENV_RESPONSE      = "VERTX_OTEL_BODY_CAPTURE_RESPONSE";
    public static final String ENV_MAX_BYTES     = "VERTX_OTEL_BODY_CAPTURE_MAX_BYTES";
    public static final String ENV_ERROR_ONLY    = "VERTX_OTEL_BODY_CAPTURE_ERROR_ONLY";
    public static final String ENV_CONTENT_TYPES = "VERTX_OTEL_BODY_CAPTURE_CONTENT_TYPES";
    public static final String ENV_INCLUDE_PATHS = "VERTX_OTEL_BODY_CAPTURE_INCLUDE_PATHS";
    public static final String ENV_EXCLUDE_PATHS = "VERTX_OTEL_BODY_CAPTURE_EXCLUDE_PATHS";

    private static final int DEFAULT_MAX_BYTES = 8192;
    private static final List<String> DEFAULT_CONTENT_TYPES = Arrays.asList(
            "application/json", "application/xml", "text/");

    // Package-private for testing: replace with a custom env lookup
    static volatile UnaryOperator<String> envProvider = System::getenv;

    private BodyCaptureConfig() {}

    public static boolean enabled() {
        String v = getenv(ENV_ENABLED);
        if (v == null) v = getenv(ENV_LEGACY);
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    public static boolean captureRequest() {
        return getBool(ENV_REQUEST, true);
    }

    public static boolean captureResponse() {
        return getBool(ENV_RESPONSE, true);
    }

    public static int maxBytes() {
        String v = getenv(ENV_MAX_BYTES);
        if (v == null) return DEFAULT_MAX_BYTES;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_BYTES;
        }
    }

    public static boolean errorOnly() {
        return getBool(ENV_ERROR_ONLY, false);
    }

    public static List<String> contentTypes() {
        String v = getenv(ENV_CONTENT_TYPES);
        if (v == null || v.isBlank()) return DEFAULT_CONTENT_TYPES;
        return Arrays.asList(v.split(","));
    }

    public static List<String> includePaths() {
        return getCsvList(ENV_INCLUDE_PATHS);
    }

    public static List<String> excludePaths() {
        return getCsvList(ENV_EXCLUDE_PATHS);
    }

    public static boolean isAllowedContentType(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase();
        for (String allowed : contentTypes()) {
            if (ct.startsWith(allowed.trim().toLowerCase())) return true;
        }
        return false;
    }

    public static boolean isAllowedPath(String path) {
        if (path == null) return false;
        for (String excluded : excludePaths()) {
            if (path.startsWith(excluded.trim())) return false;
        }
        List<String> includes = includePaths();
        if (includes.isEmpty()) return true;
        for (String included : includes) {
            if (path.startsWith(included.trim())) return true;
        }
        return false;
    }

    static String getenv(String key) {
        return envProvider.apply(key);
    }

    private static boolean getBool(String envVar, boolean defaultValue) {
        String v = getenv(envVar);
        if (v == null) return defaultValue;
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private static List<String> getCsvList(String envVar) {
        String v = getenv(envVar);
        if (v == null || v.isBlank()) return Collections.emptyList();
        return Arrays.asList(v.split(","));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -pl vertx3-rxjava2-otel-autoconfigure -Dtest=BodyCaptureConfigTest --no-transfer-progress 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add vertx3-rxjava2-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v3/BodyCaptureConfig.java \
        vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/BodyCaptureConfigTest.java
git commit -m "feat: expand BodyCaptureConfig v3 with full dotnet-parity env vars"
```

---

## Task 2: Rewrite BodyCaptureConfig (v4)

**Files:**
- Rewrite: `vertx4-rxjava3-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v4/BodyCaptureConfig.java`
- Create: `vertx4-rxjava3-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v4/BodyCaptureConfigTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// vertx4-rxjava3-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v4/BodyCaptureConfigTest.java
package io.last9.tracing.otel.v4;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BodyCaptureConfigTest {

    @AfterEach
    void resetEnv() {
        BodyCaptureConfig.envProvider = System::getenv;
    }

    @Test
    void enabledFalseByDefault() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.enabled()).isFalse();
    }

    @Test
    void enabledViaLegacyEnvVar() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_LEGACY.equals(key) ? "1" : null;
        assertThat(BodyCaptureConfig.enabled()).isTrue();
    }

    @Test
    void maxBytesDefault8192() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.maxBytes()).isEqualTo(8192);
    }

    @Test
    void isAllowedContentTypeJson() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedContentType("application/json")).isTrue();
    }

    @Test
    void isAllowedContentTypeRejectsFormData() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedContentType("multipart/form-data")).isFalse();
    }

    @Test
    void isAllowedPathNoFilters() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedPath("/api/v1/users")).isTrue();
    }

    @Test
    void isAllowedPathExclude() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_EXCLUDE_PATHS.equals(key) ? "/health" : null;
        assertThat(BodyCaptureConfig.isAllowedPath("/health")).isFalse();
        assertThat(BodyCaptureConfig.isAllowedPath("/api")).isTrue();
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

```bash
mvn test -pl vertx4-rxjava3-otel-autoconfigure -Dtest=BodyCaptureConfigTest --no-transfer-progress 2>&1 | tail -10
```

- [ ] **Step 3: Rewrite BodyCaptureConfig v4**

Create the file with the same content as Task 1 Step 3 but change the package declaration:

```java
package io.last9.tracing.otel.v4;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

public final class BodyCaptureConfig {

    public static final String ENV_LEGACY        = "VERTX_OTEL_CAPTURE_HTTP_BODY";
    public static final String ENV_ENABLED       = "VERTX_OTEL_BODY_CAPTURE_ENABLED";
    public static final String ENV_REQUEST       = "VERTX_OTEL_BODY_CAPTURE_REQUEST";
    public static final String ENV_RESPONSE      = "VERTX_OTEL_BODY_CAPTURE_RESPONSE";
    public static final String ENV_MAX_BYTES     = "VERTX_OTEL_BODY_CAPTURE_MAX_BYTES";
    public static final String ENV_ERROR_ONLY    = "VERTX_OTEL_BODY_CAPTURE_ERROR_ONLY";
    public static final String ENV_CONTENT_TYPES = "VERTX_OTEL_BODY_CAPTURE_CONTENT_TYPES";
    public static final String ENV_INCLUDE_PATHS = "VERTX_OTEL_BODY_CAPTURE_INCLUDE_PATHS";
    public static final String ENV_EXCLUDE_PATHS = "VERTX_OTEL_BODY_CAPTURE_EXCLUDE_PATHS";

    private static final int DEFAULT_MAX_BYTES = 8192;
    private static final List<String> DEFAULT_CONTENT_TYPES = Arrays.asList(
            "application/json", "application/xml", "text/");

    static volatile UnaryOperator<String> envProvider = System::getenv;

    private BodyCaptureConfig() {}

    public static boolean enabled() {
        String v = getenv(ENV_ENABLED);
        if (v == null) v = getenv(ENV_LEGACY);
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    public static boolean captureRequest() {
        return getBool(ENV_REQUEST, true);
    }

    public static boolean captureResponse() {
        return getBool(ENV_RESPONSE, true);
    }

    public static int maxBytes() {
        String v = getenv(ENV_MAX_BYTES);
        if (v == null) return DEFAULT_MAX_BYTES;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_BYTES;
        }
    }

    public static boolean errorOnly() {
        return getBool(ENV_ERROR_ONLY, false);
    }

    public static List<String> contentTypes() {
        String v = getenv(ENV_CONTENT_TYPES);
        if (v == null || v.isBlank()) return DEFAULT_CONTENT_TYPES;
        return Arrays.asList(v.split(","));
    }

    public static List<String> includePaths() {
        return getCsvList(ENV_INCLUDE_PATHS);
    }

    public static List<String> excludePaths() {
        return getCsvList(ENV_EXCLUDE_PATHS);
    }

    public static boolean isAllowedContentType(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase();
        for (String allowed : contentTypes()) {
            if (ct.startsWith(allowed.trim().toLowerCase())) return true;
        }
        return false;
    }

    public static boolean isAllowedPath(String path) {
        if (path == null) return false;
        for (String excluded : excludePaths()) {
            if (path.startsWith(excluded.trim())) return false;
        }
        List<String> includes = includePaths();
        if (includes.isEmpty()) return true;
        for (String included : includes) {
            if (path.startsWith(included.trim())) return true;
        }
        return false;
    }

    static String getenv(String key) {
        return envProvider.apply(key);
    }

    private static boolean getBool(String envVar, boolean defaultValue) {
        String v = getenv(envVar);
        if (v == null) return defaultValue;
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private static List<String> getCsvList(String envVar) {
        String v = getenv(envVar);
        if (v == null || v.isBlank()) return Collections.emptyList();
        return Arrays.asList(v.split(","));
    }
}
```

- [ ] **Step 4: Run tests**

```bash
mvn test -pl vertx4-rxjava3-otel-autoconfigure -Dtest=BodyCaptureConfigTest --no-transfer-progress 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add vertx4-rxjava3-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v4/BodyCaptureConfig.java \
        vertx4-rxjava3-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v4/BodyCaptureConfigTest.java
git commit -m "feat: expand BodyCaptureConfig v4 with full dotnet-parity env vars"
```

---

## Task 3: Add request + response body capture to NettyServerTracingHandler (v3)

This is the core of the feature. `NettyServerTracingHandler` already handles `HttpRequest` (inbound) and `HttpResponse`/`LastHttpContent` (outbound). We extend it to also handle `HttpContent` frames.

**Files:**
- Modify: `vertx3-rxjava2-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v3/agent/NettyServerTracingHandler.java`
- Create: `vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/agent/NettyServerTracingHandlerBodyTest.java`

- [ ] **Step 1: Write the failing tests**

These tests use Netty's `EmbeddedChannel` to simulate the pipeline without ByteBuddy. We manually add the `RequestHandler` and `ResponseHandler` to the channel.

```java
// vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/agent/NettyServerTracingHandlerBodyTest.java
package io.last9.tracing.otel.v3.agent;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.last9.tracing.otel.v3.BodyCaptureConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NettyServerTracingHandlerBodyTest {

    private InMemorySpanExporter spanExporter;
    private SdkTracerProvider tracerProvider;
    private EmbeddedChannel channel;
    private Span span;
    private Scope scope;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();

        // Enable body capture for all tests
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            return null;
        };

        // Create a real span and make it current on the channel
        span = tracerProvider.get("test").spanBuilder("test").setSpanKind(SpanKind.SERVER).startSpan();
        scope = span.makeCurrent();

        // Build EmbeddedChannel with our handlers directly (no ByteBuddy needed)
        channel = new EmbeddedChannel(
                new HttpServerCodec(),
                NettyServerTracingHandler.newRequestHandlerForTest(span),
                NettyServerTracingHandler.newResponseHandlerForTest()
        );
    }

    @AfterEach
    void tearDown() {
        scope.close();
        span.end();
        BodyCaptureConfig.envProvider = System::getenv;
        channel.close();
        tracerProvider.shutdown();
    }

    @Test
    void capturesRequestBody() {
        byte[] body = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        DefaultHttpRequest req = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);

        channel.writeInbound(req);
        channel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer(body)));
        channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);

        // Trigger span end via response
        DefaultHttpResponse response = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        channel.writeOutbound(response);
        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);

        span.end();
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).isNotEmpty();
        SpanData spanData = spans.get(0);
        assertThat(spanData.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("http.request.body")))
                .isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void capturesResponseBody() {
        byte[] reqBody = "{}".getBytes(StandardCharsets.UTF_8);
        DefaultHttpRequest req = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, reqBody.length);
        channel.writeInbound(req);
        channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(reqBody)));

        byte[] resBody = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        DefaultHttpResponse response = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        channel.writeOutbound(response);
        channel.writeOutbound(new DefaultHttpContent(Unpooled.wrappedBuffer(resBody)));
        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);

        span.end();
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).isNotEmpty();
        assertThat(spans.get(0).getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("http.response.body")))
                .isEqualTo("{\"result\":\"ok\"}");
    }

    @Test
    void truncatesBodyAtMaxBytes() {
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            if (BodyCaptureConfig.ENV_MAX_BYTES.equals(key)) return "10";
            return null;
        };

        byte[] body = "0123456789OVERFLOW".getBytes(StandardCharsets.UTF_8);
        DefaultHttpRequest req = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        channel.writeInbound(req);
        channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(body)));

        DefaultHttpResponse response = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        channel.writeOutbound(response);
        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);

        span.end();
        String captured = spanExporter.getFinishedSpanItems().get(0).getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("http.request.body"));
        assertThat(captured).isEqualTo("0123456789[TRUNCATED]");
    }

    @Test
    void skipsNonAllowedContentType() {
        byte[] body = "some binary data".getBytes(StandardCharsets.UTF_8);
        DefaultHttpRequest req = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
        channel.writeInbound(req);
        channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(body)));

        DefaultHttpResponse response = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        channel.writeOutbound(response);
        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);

        span.end();
        String captured = spanExporter.getFinishedSpanItems().get(0).getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("http.request.body"));
        assertThat(captured).isNull();
    }

    @Test
    void skipsBodyOnExcludedPath() {
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            if (BodyCaptureConfig.ENV_EXCLUDE_PATHS.equals(key)) return "/health";
            return null;
        };

        DefaultHttpRequest req = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health");
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        channel.writeInbound(req);
        channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);

        DefaultHttpResponse response = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        channel.writeOutbound(response);
        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);

        span.end();
        assertThat(spanExporter.getFinishedSpanItems().get(0).getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("http.request.body")))
                .isNull();
    }

    @Test
    void errorOnlySkipsBodyOn200() {
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            if (BodyCaptureConfig.ENV_ERROR_ONLY.equals(key)) return "true";
            return null;
        };

        byte[] body = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        DefaultHttpRequest req = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        channel.writeInbound(req);
        channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(body)));

        DefaultHttpResponse response = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        channel.writeOutbound(response);
        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);

        span.end();
        assertThat(spanExporter.getFinishedSpanItems().get(0).getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("http.request.body")))
                .isNull();
    }

    @Test
    void errorOnlyCapturesBodyOn500() {
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            if (BodyCaptureConfig.ENV_ERROR_ONLY.equals(key)) return "true";
            return null;
        };

        byte[] body = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        DefaultHttpRequest req = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/test");
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        channel.writeInbound(req);
        channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(body)));

        DefaultHttpResponse response = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        channel.writeOutbound(response);
        channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);

        span.end();
        assertThat(spanExporter.getFinishedSpanItems().get(0).getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("http.request.body")))
                .isEqualTo("{\"key\":\"value\"}");
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

```bash
mvn test -pl vertx3-rxjava2-otel-autoconfigure -Dtest=NettyServerTracingHandlerBodyTest --no-transfer-progress 2>&1 | tail -15
```

Expected: compile error — `newRequestHandlerForTest` and `newResponseHandlerForTest` don't exist.

- [ ] **Step 3: Add 3 new AttributeKeys and test factory methods to NettyServerTracingHandler**

In `NettyServerTracingHandler.java`, add these constants after the existing `SERVER_RESPONSE_KEY`:

```java
// Body capture channel attributes
private static final AttributeKey<Boolean> BODY_REQUEST_ACTIVE_KEY =
        AttributeKey.valueOf("last9.body.request.active");
private static final AttributeKey<ByteArrayOutputStream> BODY_REQUEST_BYTES_KEY =
        AttributeKey.valueOf("last9.body.request.bytes");
private static final AttributeKey<Boolean> BODY_REQUEST_TRUNCATED_KEY =
        AttributeKey.valueOf("last9.body.request.truncated");
private static final AttributeKey<ByteArrayOutputStream> BODY_RESPONSE_BYTES_KEY =
        AttributeKey.valueOf("last9.body.response.bytes");
private static final AttributeKey<Boolean> BODY_RESPONSE_TRUNCATED_KEY =
        AttributeKey.valueOf("last9.body.response.truncated");
```

Add these imports at the top:
```java
import io.last9.tracing.otel.v3.BodyCaptureConfig;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
```

Add these package-private factory methods for testing (at the bottom of `NettyServerTracingHandler`, before the closing `}`):

```java
/** Creates a RequestHandler with a pre-seeded span context (for unit tests). */
static ChannelInboundHandlerAdapter newRequestHandlerForTest(Span testSpan) {
    return new RequestHandler() {
        @Override
        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg)
                throws Exception {
            // Seed the context so body accumulation can read it later
            if (msg instanceof HttpRequest) {
                io.opentelemetry.context.Context otelCtx =
                        io.opentelemetry.context.Context.current().with(testSpan);
                ctx.channel().attr(SERVER_CONTEXT_KEY).set(otelCtx);
            }
            super.channelRead(ctx, msg);
        }
    };
}

/** Creates a ResponseHandler (for unit tests — same as production). */
static ChannelOutboundHandlerAdapter newResponseHandlerForTest() {
    return new ResponseHandler();
}
```

- [ ] **Step 4: Extend RequestHandler.channelRead to accumulate HttpContent**

Find `RequestHandler.channelRead()`. Currently it only handles `HttpRequest`. Add handling for `HttpContent` BEFORE the final `super.channelRead(ctx, msg)` call.

The existing method body is:
```java
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest) {
        Context otelContext = startServerSpan(ctx, (HttpRequest) msg);
        if (otelContext != null) {
            try (Scope ignored = otelContext.makeCurrent()) {
                super.channelRead(ctx, msg);
            } catch (Throwable t) {
                endServerSpan(ctx, t);
                throw t;
            }
            return;
        }
    }
    super.channelRead(ctx, msg);
}
```

Replace with:
```java
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest) {
        HttpRequest request = (HttpRequest) msg;
        Context otelContext = startServerSpan(ctx, request);
        // Decide whether to capture request body (check path + content-type once)
        if (BodyCaptureConfig.enabled() && BodyCaptureConfig.captureRequest()) {
            String ct = request.headers().get(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE);
            String uri = request.uri();
            int q = uri.indexOf('?');
            String path = q >= 0 ? uri.substring(0, q) : uri;
            boolean active = BodyCaptureConfig.isAllowedContentType(ct)
                    && BodyCaptureConfig.isAllowedPath(path);
            ctx.channel().attr(BODY_REQUEST_ACTIVE_KEY).set(active);
        }
        if (otelContext != null) {
            try (Scope ignored = otelContext.makeCurrent()) {
                super.channelRead(ctx, msg);
            } catch (Throwable t) {
                endServerSpan(ctx, t);
                throw t;
            }
            return;
        }
    }
    // Accumulate request body chunks (non-destructive: getBytes does not advance readerIndex)
    if (msg instanceof io.netty.handler.codec.http.HttpContent) {
        Boolean active = ctx.channel().attr(BODY_REQUEST_ACTIVE_KEY).get();
        if (Boolean.TRUE.equals(active)) {
            io.netty.buffer.ByteBuf content =
                    ((io.netty.handler.codec.http.HttpContent) msg).content();
            int readable = content.readableBytes();
            if (readable > 0) {
                ByteArrayOutputStream baos = ctx.channel().attr(BODY_REQUEST_BYTES_KEY).get();
                if (baos == null) {
                    baos = new ByteArrayOutputStream();
                    ctx.channel().attr(BODY_REQUEST_BYTES_KEY).set(baos);
                }
                int capacity = BodyCaptureConfig.maxBytes() - baos.size();
                if (capacity > 0) {
                    int toRead = Math.min(readable, capacity);
                    byte[] tmp = new byte[toRead];
                    content.getBytes(content.readerIndex(), tmp);
                    baos.write(tmp, 0, toRead);
                    if (toRead < readable) {
                        ctx.channel().attr(BODY_REQUEST_TRUNCATED_KEY).set(Boolean.TRUE);
                    }
                }
            }
        }
    }
    super.channelRead(ctx, msg);
}
```

- [ ] **Step 5: Extend ResponseHandler.write to accumulate HttpContent and allocate response stream**

Find `ResponseHandler.write()`. The current early-return guard is:
```java
if (!(msg instanceof HttpResponse) && !(msg instanceof LastHttpContent)) {
    super.write(ctx, msg, promise);
    return;
}
```

Replace that guard and extend the method to also handle intermediate `HttpContent` and allocate a `ByteArrayOutputStream` on `HttpResponse`:

Change the guard to:
```java
if (!(msg instanceof HttpResponse)
        && !(msg instanceof io.netty.handler.codec.http.HttpContent)) {
    super.write(ctx, msg, promise);
    return;
}
```

After the `setResponseStatus(otelContext, response)` call inside `if (msg instanceof HttpResponse)`, add:
```java
// Allocate response body accumulator if content-type is allowed
if (BodyCaptureConfig.enabled() && BodyCaptureConfig.captureResponse()) {
    String ct = response.headers().get(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE);
    if (BodyCaptureConfig.isAllowedContentType(ct)) {
        ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).set(new ByteArrayOutputStream());
    }
}
```

Before the `if (msg instanceof LastHttpContent)` block, add:
```java
// Accumulate intermediate response body chunks
if (msg instanceof io.netty.handler.codec.http.HttpContent
        && !(msg instanceof LastHttpContent)) {
    ByteArrayOutputStream baos = ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).get();
    if (baos != null) {
        io.netty.buffer.ByteBuf content =
                ((io.netty.handler.codec.http.HttpContent) msg).content();
        int readable = content.readableBytes();
        if (readable > 0) {
            int capacity = BodyCaptureConfig.maxBytes() - baos.size();
            if (capacity > 0) {
                int toRead = Math.min(readable, capacity);
                byte[] tmp = new byte[toRead];
                content.getBytes(content.readerIndex(), tmp);
                baos.write(tmp, 0, toRead);
                if (toRead < readable) {
                    ctx.channel().attr(BODY_RESPONSE_TRUNCATED_KEY).set(Boolean.TRUE);
                }
            }
        }
    }
}
```

Also capture bytes from `LastHttpContent` if it carries a body (some servers send body in the last frame):
Inside `if (msg instanceof LastHttpContent)`, BEFORE the span-end logic, add:
```java
// Capture bytes in the LastHttpContent frame itself (some encodings put body here)
ByteArrayOutputStream resLastBaos = ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).get();
if (resLastBaos != null) {
    io.netty.buffer.ByteBuf lastContent = ((LastHttpContent) msg).content();
    int readable = lastContent.readableBytes();
    if (readable > 0) {
        int capacity = BodyCaptureConfig.maxBytes() - resLastBaos.size();
        if (capacity > 0) {
            int toRead = Math.min(readable, capacity);
            byte[] tmp = new byte[toRead];
            lastContent.getBytes(lastContent.readerIndex(), tmp);
            resLastBaos.write(tmp, 0, toRead);
            if (toRead < readable) {
                ctx.channel().attr(BODY_RESPONSE_TRUNCATED_KEY).set(Boolean.TRUE);
            }
        }
    }
}
```

- [ ] **Step 6: Extend endServerSpan to set body attributes and clear channel attrs**

Find `endServerSpan()`. The current start is:
```java
private static void endServerSpan(ChannelHandlerContext ctx, Throwable error) {
    Context otelContext = ctx.channel().attr(SERVER_CONTEXT_KEY).getAndSet(null);
    ctx.channel().attr(SERVER_RESPONSE_KEY).set(null);
    if (otelContext == null) return;
```

Replace with:
```java
private static void endServerSpan(ChannelHandlerContext ctx, Throwable error) {
    Context otelContext = ctx.channel().attr(SERVER_CONTEXT_KEY).getAndSet(null);
    HttpResponse httpResponse = ctx.channel().attr(SERVER_RESPONSE_KEY).getAndSet(null);
    ByteArrayOutputStream reqBytes  = ctx.channel().attr(BODY_REQUEST_BYTES_KEY).getAndSet(null);
    Boolean reqTruncated            = ctx.channel().attr(BODY_REQUEST_TRUNCATED_KEY).getAndSet(null);
    ByteArrayOutputStream resBytes  = ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).getAndSet(null);
    Boolean resTruncated            = ctx.channel().attr(BODY_RESPONSE_TRUNCATED_KEY).getAndSet(null);
    ctx.channel().attr(BODY_REQUEST_ACTIVE_KEY).set(null);
    if (otelContext == null) return;
    try {
        Span span = Span.fromContext(otelContext);
        // Attach body attributes before ending span
        if (BodyCaptureConfig.enabled() && span.isRecording()) {
            int statusCode = httpResponse != null ? httpResponse.status().code() : 0;
            boolean shouldAttach = !BodyCaptureConfig.errorOnly() || statusCode >= 400;
            if (shouldAttach) {
                if (reqBytes != null && reqBytes.size() > 0) {
                    String body = reqBytes.toString(StandardCharsets.UTF_8);
                    if (Boolean.TRUE.equals(reqTruncated)) body += "[TRUNCATED]";
                    span.setAttribute("http.request.body", body);
                }
                if (resBytes != null && resBytes.size() > 0) {
                    String body = resBytes.toString(StandardCharsets.UTF_8);
                    if (Boolean.TRUE.equals(resTruncated)) body += "[TRUNCATED]";
                    span.setAttribute("http.response.body", body);
                }
            }
        }
        if (error != null) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error.getMessage());
        }
        span.end();
    } catch (Throwable t) {
        log.warn("NettyServerTracingHandler: failed to end span: {}", t.getMessage());
    }
}
```

Also update the old `ctx.channel().attr(SERVER_RESPONSE_KEY).set(null)` line that was replaced — it's now captured via `getAndSet(null)` above. Make sure there are no other references to `SERVER_RESPONSE_KEY.set(null)` in the file.

- [ ] **Step 7: Run the body tests**

```bash
mvn test -pl vertx3-rxjava2-otel-autoconfigure -Dtest=NettyServerTracingHandlerBodyTest --no-transfer-progress 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all 7 tests PASS.

- [ ] **Step 8: Run the full v3 test suite to check for regressions**

```bash
mvn test -pl vertx3-rxjava2-otel-autoconfigure --no-transfer-progress 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add vertx3-rxjava2-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v3/agent/NettyServerTracingHandler.java \
        vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/agent/NettyServerTracingHandlerBodyTest.java
git commit -m "feat: capture request/response bodies in NettyServerTracingHandler (v3 agent)"
```

---

## Task 4: Fix TracedRouter v3 library path

The unstaged `TracedRouter.java` has partial body capture using the old single-field `BodyCaptureConfig`. Replace with the new API. Also remove the old `isTextOrJson()` and `trunc()` helper methods.

**Files:**
- Modify: `vertx3-rxjava2-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v3/TracedRouter.java`
- Create: `vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/TracedRouterBodyCaptureTest.java`

- [ ] **Step 1: Write the failing test**

```java
// vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/TracedRouterBodyCaptureTest.java
package io.last9.tracing.otel.v3;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.plugins.RxJavaPlugins;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.client.WebClient;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
class TracedRouterBodyCaptureTest {

    private TestOtelSetup otel;
    private InMemorySpanExporter spanExporter;
    private Vertx vertx;
    private WebClient webClient;
    private int port;

    @BeforeEach
    void setUp(VertxTestContext ctx) throws Exception {
        RxJavaPlugins.reset();
        resetInstalledFlag();
        otel = new TestOtelSetup();
        otel.install();
        spanExporter = otel.getSpanExporter();

        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            return null;
        };

        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);

        Router router = TracedRouter.create(vertx, otel.getOpenTelemetry());

        router.post("/api/echo")
                .handler(BodyHandler.create())
                .handler(routerCtx -> {
                    routerCtx.response()
                            .putHeader("content-type", "application/json")
                            .end(routerCtx.getBodyAsString());
                });

        vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .doOnSuccess(server -> port = server.actualPort())
                .subscribe(server -> ctx.completeNow(), ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        BodyCaptureConfig.envProvider = System::getenv;
        otel.shutdown();
        vertx.close(v -> ctx.completeNow());
    }

    @Test
    void capturesRequestBodyFromBodyHandler(VertxTestContext ctx) throws Exception {
        JsonObject payload = new JsonObject().put("hello", "world");

        webClient.post(port, "localhost", "/api/echo")
                .putHeader("content-type", "application/json")
                .rxSendJsonObject(payload)
                .subscribe(resp -> {
                    List<SpanData> spans = waitForSpans(spanExporter);
                    SpanData span = spans.stream()
                            .filter(s -> s.getName().contains("/api/echo"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("No span for /api/echo"));

                    assertThat(span.getAttributes().get(
                            io.opentelemetry.api.common.AttributeKey.stringKey("http.request.body")))
                            .contains("hello");
                    ctx.completeNow();
                }, ctx::failNow);

        assertThat(ctx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void skipsBodyOnExcludedPath(VertxTestContext ctx) throws Exception {
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            if (BodyCaptureConfig.ENV_EXCLUDE_PATHS.equals(key)) return "/api";
            return null;
        };

        JsonObject payload = new JsonObject().put("hello", "world");

        webClient.post(port, "localhost", "/api/echo")
                .putHeader("content-type", "application/json")
                .rxSendJsonObject(payload)
                .subscribe(resp -> {
                    List<SpanData> spans = waitForSpans(spanExporter);
                    SpanData span = spans.stream()
                            .filter(s -> s.getName().contains("/api/echo"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("No span for /api/echo"));

                    assertThat(span.getAttributes().get(
                            io.opentelemetry.api.common.AttributeKey.stringKey("http.request.body")))
                            .isNull();
                    ctx.completeNow();
                }, ctx::failNow);

        assertThat(ctx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    // ---- helpers ----

    private static List<SpanData> waitForSpans(InMemorySpanExporter exporter) {
        for (int i = 0; i < 50; i++) {
            List<SpanData> spans = exporter.getFinishedSpanItems();
            if (!spans.isEmpty()) return spans;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("No spans after 5s");
    }

    private static void resetInstalledFlag() throws Exception {
        Field f = OtelLauncher.class.getDeclaredField("installed");
        f.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) f.get(null)).set(false);
    }
}
```

- [ ] **Step 2: Update TracedRouter v3**

In `TracedRouter.java`, find the unstaged body capture block (lines ~247–257 from the earlier diff):

```java
// --- HTTP body capture: request body only (if BodyHandler used) ---
if (BodyCaptureConfig.enabled()) {
    String contentType = ctx.request().getHeader("content-type");
    if (ctx.getBody() != null && isTextOrJson(contentType)) {
        String reqBody = trunc(ctx.getBody().toString("UTF-8"));
        span.setAttribute("http.request.body", reqBody);
    }
    // Response body capture would require custom buffering, not supported by default
}
```

Replace with:
```java
// Library-path request body capture (requires BodyHandler on the route)
if (BodyCaptureConfig.enabled() && BodyCaptureConfig.captureRequest()) {
    io.vertx.reactivex.core.buffer.Buffer body = ctx.getBody();
    String contentType = ctx.request().getHeader("content-type");
    String path = ctx.normalisedPath();
    if (body != null
            && BodyCaptureConfig.isAllowedContentType(contentType)
            && BodyCaptureConfig.isAllowedPath(path)) {
        String bodyStr = body.toString("UTF-8");
        int max = BodyCaptureConfig.maxBytes();
        if (bodyStr.length() > max) {
            bodyStr = bodyStr.substring(0, max) + "[TRUNCATED]";
        }
        span.setAttribute("http.request.body", bodyStr);
    }
}
```

Remove the old helper methods from `TracedRouter.java`:
```java
// DELETE these two methods:
private static boolean isTextOrJson(String contentType) { ... }
private static String trunc(String s) { ... }
```

Also remove the `// --- END BODY CAPTURE ---` comment if present.

- [ ] **Step 3: Run tests**

```bash
mvn test -pl vertx3-rxjava2-otel-autoconfigure -Dtest=TracedRouterBodyCaptureTest --no-transfer-progress 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Full v3 suite**

```bash
mvn test -pl vertx3-rxjava2-otel-autoconfigure --no-transfer-progress 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add vertx3-rxjava2-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v3/TracedRouter.java \
        vertx3-rxjava2-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v3/TracedRouterBodyCaptureTest.java
git commit -m "feat: update TracedRouter v3 library-path body capture to new BodyCaptureConfig"
```

---

## Task 5: Fix SpanNameUpdater v4 + add library-path body capture

The unstaged `SpanNameUpdater.java` has duplicate helper methods. Remove them, update to new `BodyCaptureConfig`.

**Files:**
- Modify: `vertx4-rxjava3-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v4/SpanNameUpdater.java`
- Create: `vertx4-rxjava3-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v4/SpanNameUpdaterBodyCaptureTest.java`

- [ ] **Step 1: Write failing test**

```java
// vertx4-rxjava3-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v4/SpanNameUpdaterBodyCaptureTest.java
package io.last9.tracing.otel.v4;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.ext.web.Router;
import io.vertx.rxjava3.ext.web.client.WebClient;
import io.vertx.rxjava3.ext.web.handler.BodyHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
class SpanNameUpdaterBodyCaptureTest {

    private InMemorySpanExporter spanExporter;
    private SdkTracerProvider tracerProvider;
    private Vertx vertx;
    private WebClient webClient;
    private int port;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        spanExporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();

        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            return null;
        };

        vertx = Vertx.vertx();
        webClient = WebClient.create(vertx);

        Router router = Router.router(vertx);
        // Manually add SpanNameUpdater (normally wired by agent's RxRouterAdvice)
        SpanNameUpdater.addToAllRoutes(router);

        router.post("/api/data")
                .handler(BodyHandler.create())
                .handler(routerCtx -> {
                    // Simulate a SERVER span being current (agent would do this via VertxTracer)
                    Span testSpan = tracerProvider.get("test")
                            .spanBuilder("POST /api/data")
                            .setSpanKind(SpanKind.SERVER)
                            .startSpan();
                    try (Scope ignored = testSpan.makeCurrent()) {
                        SpanNameUpdater.captureRequestBody(routerCtx);
                        routerCtx.response().setStatusCode(200).end("ok");
                    } finally {
                        testSpan.end();
                    }
                });

        vertx.createHttpServer()
                .requestHandler(router)
                .rxListen(0)
                .subscribe(server -> {
                    port = server.actualPort();
                    ctx.completeNow();
                }, ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        BodyCaptureConfig.envProvider = System::getenv;
        tracerProvider.shutdown();
        vertx.close().subscribe(() -> ctx.completeNow(), ctx::failNow);
    }

    @Test
    void capturesRequestBodyWhenBodyHandlerPresent(VertxTestContext ctx) throws Exception {
        JsonObject payload = new JsonObject().put("name", "test");

        webClient.post(port, "localhost", "/api/data")
                .putHeader("content-type", "application/json")
                .rxSendJsonObject(payload)
                .subscribe(resp -> {
                    List<SpanData> spans = waitForSpans();
                    assertThat(spans).isNotEmpty();
                    String body = spans.get(0).getAttributes().get(
                            io.opentelemetry.api.common.AttributeKey.stringKey("http.request.body"));
                    assertThat(body).contains("name");
                    ctx.completeNow();
                }, ctx::failNow);

        assertThat(ctx.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    private List<SpanData> waitForSpans() {
        for (int i = 0; i < 50; i++) {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            if (!spans.isEmpty()) return spans;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("No spans after 5s");
    }
}
```

- [ ] **Step 2: Fix SpanNameUpdater v4**

Open `SpanNameUpdater.java`. The unstaged diff shows duplicate `isTextOrJson()` and `trunc()` methods near the bottom. Remove ALL occurrences of both methods. Then add the following body capture logic.

Add a new static method `captureRequestBody` at the end of the class (before the closing `}`):

```java
/**
 * Captures the HTTP request body as a span attribute if body capture is enabled
 * and the route/content-type passes the configured filters.
 * Requires {@code BodyHandler} to have run before this is called.
 *
 * @param ctx the routing context
 */
public static void captureRequestBody(io.vertx.rxjava3.ext.web.RoutingContext ctx) {
    if (!BodyCaptureConfig.enabled() || !BodyCaptureConfig.captureRequest()) return;
    io.vertx.rxjava3.core.buffer.Buffer body = ctx.body().buffer();
    if (body == null) return;
    String contentType = ctx.request().getHeader("content-type");
    String path = ctx.normalizedPath();
    if (!BodyCaptureConfig.isAllowedContentType(contentType)
            || !BodyCaptureConfig.isAllowedPath(path)) return;

    Span span = Span.current();
    if (span == null || !span.isRecording()) return;

    String bodyStr = body.toString("UTF-8");
    int max = BodyCaptureConfig.maxBytes();
    if (bodyStr.length() > max) {
        bodyStr = bodyStr.substring(0, max) + "[TRUNCATED]";
    }
    span.setAttribute("http.request.body", bodyStr);
}
```

Also wire `captureRequestBody` into `updateSpanName()` so it runs automatically when `SpanNameUpdater` is used. At the end of `updateSpanName()` (before `ctx.next()`), add:

```java
captureRequestBody(ctx);
```

- [ ] **Step 3: Run the tests**

```bash
mvn test -pl vertx4-rxjava3-otel-autoconfigure -Dtest=SpanNameUpdaterBodyCaptureTest --no-transfer-progress 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Full v4 suite**

```bash
mvn test -pl vertx4-rxjava3-otel-autoconfigure --no-transfer-progress 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add vertx4-rxjava3-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v4/SpanNameUpdater.java \
        vertx4-rxjava3-otel-autoconfigure/src/test/java/io/last9/tracing/otel/v4/SpanNameUpdaterBodyCaptureTest.java
git commit -m "feat: add library-path request body capture to SpanNameUpdater v4, fix duplicate helpers"
```

---

## Task 6: Full build + regression check

- [ ] **Step 1: Run all tests across all modules**

```bash
mvn test -B --no-transfer-progress 2>&1 | tail -30
```

Expected: `BUILD SUCCESS` across all modules.

- [ ] **Step 2: Build fat JARs**

```bash
mvn package -B --no-transfer-progress -DskipTests 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`. Agent JARs present in `vertx3-otel-agent/target/` and `vertx4-otel-agent/target/`.

- [ ] **Step 3: Commit any remaining unstaged files**

```bash
git status --short
```

Stage and commit any remaining modified files not yet committed (e.g., `SpanNameUpdaterTest.java`, `TestOtelSetup.java`, `HttpTracerHelperTest.java` if they were modified as part of earlier work):

```bash
git add -p   # review and stage selectively
git commit -m "chore: clean up remaining unstaged test changes on errors branch"
```

---

## Spec coverage check

| Spec requirement | Task |
|---|---|
| All 9 env vars + deprecated alias | Tasks 1, 2 |
| `http.request.body` span attribute | Task 3 (agent), Task 4 (library v3), Task 5 (library v4) |
| `http.response.body` span attribute | Task 3 (agent v3 only) |
| Truncation with `[TRUNCATED]` suffix | Task 3 (Step 3, test: `truncatesBodyAtMaxBytes`) |
| Content-type filtering | Task 3 (test: `skipsNonAllowedContentType`) |
| Path filtering (include/exclude) | Task 3 (test: `skipsBodyOnExcludedPath`), Task 4 (test: `skipsBodyOnExcludedPath`) |
| `ERROR_ONLY` mode | Task 3 (tests: `errorOnlySkipsBodyOn200`, `errorOnlyCapturesBodyOn500`) |
| Zero user code change (v3 agent) | Task 3 — Netty handler, no new ByteBuddy transformers needed |
| Library path request body (v3) | Task 4 |
| Library path request body (v4) | Task 5 |
| v4 agent response body | **Deferred** — see scope note at top of plan |

---

## Known limitations

- **v4 agent response body**: not implemented. The VertxTracer SPI creates SERVER spans after Netty decoding, so `Span.current()` is not the SERVER span when `HttpRequest` arrives at the Netty layer. A future implementation could hook `OpenTelemetryTracer.sendResponse()` via ByteBuddy.
- **Library-path response body** (both v3/v4): response stream is consumed before the end handler runs. Agent path required.
- **Multipart / binary bodies**: filtered out by default `CONTENT_TYPES`. Users can extend the list but binary content will produce garbled UTF-8 strings.
