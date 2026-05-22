# HTTP Body Capture — Design Spec

**Date**: 2026-05-22  
**Branch**: errors  
**Reference**: https://github.com/last9/dotnet-otel-body-capture  
**Scope**: Full feature parity with the .NET body capture library for both Vert.x 3 (RxJava 2) and Vert.x 4 (RxJava 3) modules.

---

## Goal

Capture HTTP request and response bodies as OpenTelemetry span attributes with zero code changes required from users. Users running the `-javaagent` get full request + response body capture automatically. Users on the library path (`TracedRouter`/`SpanNameUpdater`) get request body capture automatically when `BodyHandler` is present.

---

## Config

All options controlled via environment variables. Existing `VERTX_OTEL_CAPTURE_HTTP_BODY` kept as deprecated alias for `VERTX_OTEL_BODY_CAPTURE_ENABLED`.

| Env var | Type | Default | Description |
|---|---|---|---|
| `VERTX_OTEL_BODY_CAPTURE_ENABLED` | bool | `false` | Global kill switch |
| `VERTX_OTEL_BODY_CAPTURE_REQUEST` | bool | `true` | Capture request body |
| `VERTX_OTEL_BODY_CAPTURE_RESPONSE` | bool | `true` | Capture response body |
| `VERTX_OTEL_BODY_CAPTURE_MAX_BYTES` | int | `8192` | Truncate at this byte count |
| `VERTX_OTEL_BODY_CAPTURE_ERROR_ONLY` | bool | `false` | Only capture on 4xx/5xx responses |
| `VERTX_OTEL_BODY_CAPTURE_CONTENT_TYPES` | csv | `application/json,application/xml,text/` | Capture only these content-type prefixes |
| `VERTX_OTEL_BODY_CAPTURE_INCLUDE_PATHS` | csv | `` (all) | Only capture these path prefixes; empty = all |
| `VERTX_OTEL_BODY_CAPTURE_EXCLUDE_PATHS` | csv | `` (none) | Skip these path prefixes (e.g. `/health,/metrics`) |

**Span attributes written:**
- `http.request.body` — captured request body string
- `http.response.body` — captured response body string

Bodies exceeding `MAX_BYTES` are truncated with a `[TRUNCATED]` suffix.

---

## Architecture

### New / modified files

| File | Action | Notes |
|---|---|---|
| `v3/BodyCaptureConfig.java` | Modify | Expand from 2 fields to full config |
| `v4/BodyCaptureConfig.java` | Modify | Same expansion |
| `v3/BodyAccumulator.java` | New | Per-request byte accumulator |
| `v4/BodyAccumulator.java` | New | Same for v4 |
| `v3/agent/HttpServerRequestBodyAdvice.java` | New | ByteBuddy advice — request chunks |
| `v3/agent/HttpServerResponseBodyAdvice.java` | New | ByteBuddy advice — response writes |
| `v4/agent/HttpServerRequestBodyAdvice.java` | New | ByteBuddy advice — request chunks (v4 impl class) |
| `v4/agent/HttpServerResponseBodyAdvice.java` | New | ByteBuddy advice — response writes (v4 impl class) |
| `v3/agent/HttpServerAdviceHelper.java` | Modify | Read accumulators in bodyEndHandler, set span attrs |
| `v3/agent/OtelLauncher.java` | Modify | Register new advice transformers |
| `v4/agent/OtelLauncher.java` | Modify | Register new advice transformers |
| `v3/TracedRouter.java` | Modify | Read ctx.getBody() → set http.request.body |
| `v4/SpanNameUpdater.java` | Modify | Read ctx.getBody() → set http.request.body |

---

## Component Details

### BodyCaptureConfig

Static utility — reads env vars once at class load (consistent with existing pattern).

```java
public final class BodyCaptureConfig {
    // deprecated alias
    public static final String ENV_LEGACY = "VERTX_OTEL_CAPTURE_HTTP_BODY";
    public static final String ENV_ENABLED = "VERTX_OTEL_BODY_CAPTURE_ENABLED";
    public static final String ENV_REQUEST = "VERTX_OTEL_BODY_CAPTURE_REQUEST";
    public static final String ENV_RESPONSE = "VERTX_OTEL_BODY_CAPTURE_RESPONSE";
    public static final String ENV_MAX_BYTES = "VERTX_OTEL_BODY_CAPTURE_MAX_BYTES";
    public static final String ENV_ERROR_ONLY = "VERTX_OTEL_BODY_CAPTURE_ERROR_ONLY";
    public static final String ENV_CONTENT_TYPES = "VERTX_OTEL_BODY_CAPTURE_CONTENT_TYPES";
    public static final String ENV_INCLUDE_PATHS = "VERTX_OTEL_BODY_CAPTURE_INCLUDE_PATHS";
    public static final String ENV_EXCLUDE_PATHS = "VERTX_OTEL_BODY_CAPTURE_EXCLUDE_PATHS";

    public static boolean enabled()        { ... }
    public static boolean captureRequest() { ... }
    public static boolean captureResponse(){ ... }
    public static int maxBytes()           { ... }  // default 8192
    public static boolean errorOnly()      { ... }
    public static List<String> contentTypes() { ... }
    public static List<String> includePaths() { ... }
    public static List<String> excludePaths() { ... }

    public static boolean isAllowedContentType(String contentType) { ... }
    public static boolean isAllowedPath(String path) { ... }
}
```

`isAllowedContentType`: returns true if contentType starts with any entry in `contentTypes()`.  
`isAllowedPath`: returns false if path starts with any `excludePaths()` entry; if `includePaths()` non-empty, also returns false if path doesn't start with any `includePaths()` entry.

### BodyAccumulator

Stores in-flight request/response bytes. Keys are object identity (request/response instances). `WeakHashMap` prevents memory leaks — entries GC'd when request/response objects are collected. Single-threaded per event loop, no synchronization needed.

```java
public final class BodyAccumulator {
    // WeakHashMap<Object, byte[]> per direction
    
    public static void appendRequest(Object request, byte[] chunk)   { ... }
    public static void appendResponse(Object response, byte[] chunk) { ... }
    
    // Returns truncated string, or null if nothing accumulated / content-type filtered
    public static String getRequest(Object request)   { ... }
    public static String getResponse(Object response) { ... }
    
    public static void clear(Object request, Object response) { ... }
}
```

- `append*`: if accumulated size already >= `maxBytes()`, skip chunk. Stop early — no unbounded growth.
- `get*`: returns `accumulated + "[TRUNCATED]"` if original exceeded limit, otherwise full string.
- Content-type check: `append*` only accumulates if `BodyCaptureConfig.isAllowedContentType()` passes for the first chunk's associated content-type. Content-type stored at first-append time.

### ByteBuddy Advice — Request Body (v3)

Target class: `io.vertx.core.http.impl.HttpServerRequestImpl`  
Target method: `handler(io.vertx.core.Handler)`

```java
public class HttpServerRequestBodyAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
            @Advice.This Object request,
            @Advice.Argument(value = 0, readOnly = false) Handler<Buffer> handler) {
        handler = HttpServerRequestBodyHelper.wrap(request, handler);
    }
}
```

`HttpServerRequestBodyHelper.wrap()`:
- If body capture disabled or request path excluded → return original handler unchanged
- Otherwise return a wrapping `Handler<Buffer>` that:
  1. Calls `BodyAccumulator.appendRequest(request, buffer.getBytes())`
  2. Calls `original.handle(buffer)` (user's handler still receives data)

### ByteBuddy Advice — Response Body (v3)

Target class: `io.vertx.core.http.impl.HttpServerResponseImpl`  
Target methods: `write(io.vertx.core.buffer.Buffer)`, `end(io.vertx.core.buffer.Buffer)`, `end(String)`, `write(String)`

```java
public class HttpServerResponseBodyAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
            @Advice.This Object response,
            @Advice.Argument(0) Object body) {
        HttpServerResponseBodyHelper.capture(response, body);
    }
}
```

`HttpServerResponseBodyHelper.capture()`:
- Check `BodyCaptureConfig.captureResponse()` and content-type of response
- `BodyAccumulator.appendResponse(response, bytes)`

### HttpServerAdviceHelper — integration point

In the existing `response.bodyEndHandler()` (already present for `span.end()`), add:

```java
response.bodyEndHandler(v -> {
    if (BodyCaptureConfig.enabled()) {
        int status = response.getStatusCode();
        boolean shouldCapture = !BodyCaptureConfig.errorOnly() || status >= 400;
        if (shouldCapture) {
            String reqBody = BodyAccumulator.getRequest(request);
            String resBody = BodyAccumulator.getResponse(response);
            if (reqBody != null) span.setAttribute("http.request.body", reqBody);
            if (resBody != null) span.setAttribute("http.response.body", resBody);
        }
        BodyAccumulator.clear(request, response);
    }
    if (span.isRecording()) span.end();
});
```

### v4 differences

Vert.x 4 uses `io.vertx.core.http.impl.Http1xServerRequest` (request) and `io.vertx.core.http.impl.Http1xServerResponse` (response). Advice classes are identical in structure but target these class names.

v4 does not have an `HttpServerAdviceHelper` for SERVER spans (those come from the VertxTracer SPI). Body attributes for agent path: attach via a `SpanNameUpdater` hook or a new `Http1xServerRequestAdvice` that hooks the `VertxTracer`'s span lifecycle. **Implementation detail to confirm during build.**

### Library path (v3 TracedRouter, v4 SpanNameUpdater)

In the existing end handler of `TracedRouter` (v3) and `updateSpanName` (v4):

```java
if (BodyCaptureConfig.enabled() && BodyCaptureConfig.captureRequest()) {
    Buffer body = ctx.getBody();  // non-null only if BodyHandler present
    String path = ctx.request().path();
    String ct = ctx.request().getHeader("content-type");
    if (body != null
            && BodyCaptureConfig.isAllowedContentType(ct)
            && BodyCaptureConfig.isAllowedPath(path)) {
        String s = trunc(body.toString("UTF-8"));
        span.setAttribute("http.request.body", s);
    }
}
```

Response body on library path: **not supported** — response stream is already consumed before end handler runs. Agent path required for response body.

---

## Data flow

```
Incoming request (agent path)
  ↓
HttpServerAdviceHelper.wrapHandler()     ← ByteBuddy on HttpServer.requestHandler()
  → creates SERVER span
  ↓
user calls request.handler(myChunkHandler)
  ↓
HttpServerRequestBodyAdvice              ← ByteBuddy on HttpServerRequestImpl.handler()
  → wraps myChunkHandler with capturing wrapper
  ↓
Vert.x delivers Buffer chunks
  → BodyAccumulator.appendRequest(request, chunk)
  → myChunkHandler.handle(chunk)         ← user's code unaffected
  ↓
user's handler calls response.write(buffer) / response.end(buffer)
  ↓
HttpServerResponseBodyAdvice             ← ByteBuddy on HttpServerResponseImpl.write/end
  → BodyAccumulator.appendResponse(response, bytes)
  ↓
response.bodyEndHandler fires
  → read accumulators → set span attributes
  → span.end()
  → BodyAccumulator.clear()
```

---

## Capability matrix

| | Request body | Response body |
|---|---|---|
| Agent — v3 | ByteBuddy on `HttpServerRequestImpl.handler()` | ByteBuddy on `HttpServerResponseImpl.write/end()` |
| Agent — v4 | ByteBuddy on `Http1xServerRequest.handler()` | ByteBuddy on `Http1xServerResponse.write/end()` |
| Library — v3 TracedRouter | `ctx.getBody()` (requires BodyHandler) | Not supported |
| Library — v4 SpanNameUpdater | `ctx.getBody()` (requires BodyHandler) | Not supported |

---

## Testing

### Unit tests
- `BodyCaptureConfigTest` — all env vars, defaults, deprecated alias, content-type/path helpers
- `BodyAccumulatorTest` — truncation, `[TRUNCATED]` suffix, content-type skip, path skip, clear releases

### Integration tests (per module)
- `BodyCaptureRequestTest` — POST JSON, assert `http.request.body` on span
- `BodyCaptureResponseTest` — agent path, assert `http.response.body` on span
- `BodyCaptureErrorOnlyTest` — `ERROR_ONLY=true`, body absent on 200, present on 500
- `BodyCapturePathFilterTest` — exclude `/health`, assert no body attr on excluded path
- `BodyCaptureSizeTest` — body > 8192 bytes → `[TRUNCATED]` suffix
- `BodyCaptureContentTypeTest` — `text/xml` captured, `multipart/form-data` skipped

### E2E (docker-compose example app)
- Set `VERTX_OTEL_BODY_CAPTURE_ENABLED=true`
- POST JSON to route → verify `http.request.body` + `http.response.body` in collector debug output
- POST body > 8192 bytes → verify `[TRUNCATED]`
- Hit `/health` with `EXCLUDE_PATHS=/health` → verify no body attrs

---

## Open questions

1. **v4 agent SERVER span lifecycle**: Vert.x 4 VertxTracer SPI owns span creation — need to confirm where to hook `bodyEndHandler` for v4 agent path. May require a new advice on `Http1xServerRequest` directly rather than hooking the SPI.
2. **Vert.x 3 impl class name**: Confirm `HttpServerRequestImpl` is the correct class in Vert.x 3.9.16 (not renamed in that patch version).
3. **Existing unstaged changes on `errors` branch**: v4 `SpanNameUpdater` has duplicate helper methods — fix as part of this work.
