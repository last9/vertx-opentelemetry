# HTTP Body Capture — Design Spec

**Date**: 2026-05-22 (grilled: 2026-05-23)
**Branch**: errors
**Reference**: https://github.com/last9/dotnet-otel-body-capture
**Scope**: Full feature parity with the .NET body capture library for both Vert.x 3 (RxJava 2) and Vert.x 4 (RxJava 3) modules.

---

## Goal

Capture HTTP request and response bodies as OpenTelemetry span attributes with zero code changes required from users. Users running the `-javaagent` get full request + response body capture automatically. Users on the library path (`TracedRouter`/`SpanNameUpdater`) get request body capture when `BodyHandler` is present (response body requires agent path).

---

## Config

All options controlled via environment variables. Existing `VERTX_OTEL_CAPTURE_HTTP_BODY` kept as deprecated alias for `VERTX_OTEL_BODY_CAPTURE_ENABLED` — no breakage.

| Env var | Type | Default | Description |
|---|---|---|---|
| `VERTX_OTEL_BODY_CAPTURE_ENABLED` | bool | `false` | Global kill switch |
| `VERTX_OTEL_BODY_CAPTURE_REQUEST` | bool | `true` | Capture request body |
| `VERTX_OTEL_BODY_CAPTURE_RESPONSE` | bool | `true` | Capture response body |
| `VERTX_OTEL_BODY_CAPTURE_MAX_BYTES` | int | `8192` | Truncate at this byte count |
| `VERTX_OTEL_BODY_CAPTURE_ERROR_ONLY` | bool | `false` | Only attach on 4xx/5xx responses |
| `VERTX_OTEL_BODY_CAPTURE_CONTENT_TYPES` | csv | `application/json,application/xml,text/` | Capture only these content-type prefixes |
| `VERTX_OTEL_BODY_CAPTURE_INCLUDE_PATHS` | csv | `` (all) | Only capture these path prefixes; empty = all |
| `VERTX_OTEL_BODY_CAPTURE_EXCLUDE_PATHS` | csv | `` (none) | Skip these path prefixes (e.g. `/health,/metrics`) |

**Span attributes written:**
- `http.request.body` — captured request body string (UTF-8)
- `http.response.body` — captured response body string (UTF-8)

Bodies exceeding `MAX_BYTES` are truncated with a `[TRUNCATED]` suffix (dotnet parity).
Charset is always UTF-8 — configured `CONTENT_TYPES` defaults cover JSON/XML/text which are all UTF-8 in practice.

---

## Architecture

### Approach: extend `NettyServerTracingHandler` (agent path)

The v3 agent already injects `NettyServerTracingHandler` into the Netty pipeline via `NettyServerPipelineAdvice`. This handler has:
- `RequestHandler` (inbound) — creates SERVER span, fires on `HttpRequest` frames
- `ResponseHandler` (outbound) — sets status, ends span on `LastHttpContent`

Body capture extends BOTH handlers to also process `HttpContent` frames, which carry the actual body bytes. No new ByteBuddy advice classes needed — everything lives inside the existing `NettyServerTracingHandler`.

### Why not ByteBuddy on Vert.x impl classes

Targeting `io.vertx.core.http.impl.HttpServerRequestImpl.handler(Handler<Buffer>)` (Approach B from brainstorm) requires wrapping the user's chunk handler — more invasive and fragile across Vert.x patch versions. The Netty pipeline handlers already see all bytes non-destructively: they read and pass frames downstream unchanged. Extending the existing Netty infrastructure is the natural fit.

### Channel attributes (3 new `AttributeKey`s)

| Key | Type | Purpose |
|---|---|---|
| `last9.body.request.active` | `Boolean` | Whether to accumulate request bytes (path + content-type filter result) |
| `last9.body.request.bytes` | `ByteArrayOutputStream` | Accumulated request body bytes |
| `last9.body.response.bytes` | `ByteArrayOutputStream` | Accumulated response body bytes |

Response active state is not stored separately — `last9.body.response.bytes` being non-null signals active. Content-type check on response headers (`HttpResponse` frame) either allocates the stream or skips.

---

## Component Details

### BodyCaptureConfig (both v3 and v4)

Static utility — reads env vars once at class load (consistent with existing pattern).

```java
public final class BodyCaptureConfig {
    // deprecated alias
    public static final String ENV_LEGACY   = "VERTX_OTEL_CAPTURE_HTTP_BODY";
    public static final String ENV_ENABLED  = "VERTX_OTEL_BODY_CAPTURE_ENABLED";
    public static final String ENV_REQUEST  = "VERTX_OTEL_BODY_CAPTURE_REQUEST";
    public static final String ENV_RESPONSE = "VERTX_OTEL_BODY_CAPTURE_RESPONSE";
    public static final String ENV_MAX_BYTES      = "VERTX_OTEL_BODY_CAPTURE_MAX_BYTES";
    public static final String ENV_ERROR_ONLY     = "VERTX_OTEL_BODY_CAPTURE_ERROR_ONLY";
    public static final String ENV_CONTENT_TYPES  = "VERTX_OTEL_BODY_CAPTURE_CONTENT_TYPES";
    public static final String ENV_INCLUDE_PATHS  = "VERTX_OTEL_BODY_CAPTURE_INCLUDE_PATHS";
    public static final String ENV_EXCLUDE_PATHS  = "VERTX_OTEL_BODY_CAPTURE_EXCLUDE_PATHS";

    public static boolean enabled()           { ... }
    public static boolean captureRequest()    { ... }
    public static boolean captureResponse()   { ... }
    public static int maxBytes()              { ... }  // default 8192
    public static boolean errorOnly()         { ... }
    public static List<String> contentTypes() { ... }
    public static List<String> includePaths() { ... }
    public static List<String> excludePaths() { ... }

    public static boolean isAllowedContentType(String contentType) { ... }
    public static boolean isAllowedPath(String path) { ... }
}
```

`isAllowedContentType`: true if contentType starts with any entry in `contentTypes()`.
`isAllowedPath`: false if path starts with any `excludePaths()` entry; if `includePaths()` non-empty, also false if path doesn't start with any `includePaths()` entry.

### NettyServerTracingHandler — extended (v3)

**`RequestHandler.channelRead()` changes:**

On `HttpRequest` frame (existing):
```java
// NEW: decide whether to accumulate request body
if (BodyCaptureConfig.enabled() && BodyCaptureConfig.captureRequest()) {
    String ct = request.headers().get("Content-Type");
    String path = extractPath(request.uri());
    boolean active = BodyCaptureConfig.isAllowedContentType(ct)
                  && BodyCaptureConfig.isAllowedPath(path);
    ctx.channel().attr(BODY_REQUEST_ACTIVE_KEY).set(active);
}
```

On `HttpContent` frame (NEW):
```java
if (msg instanceof HttpContent) {
    Boolean active = ctx.channel().attr(BODY_REQUEST_ACTIVE_KEY).get();
    if (Boolean.TRUE.equals(active)) {
        ByteArrayOutputStream baos = ctx.channel().attr(BODY_REQUEST_BYTES_KEY).get();
        if (baos == null) {
            baos = new ByteArrayOutputStream();
            ctx.channel().attr(BODY_REQUEST_BYTES_KEY).set(baos);
        }
        ByteBuf content = ((HttpContent) msg).content();
        int readable = content.readableBytes();
        int capacity = BodyCaptureConfig.maxBytes() - baos.size();
        if (capacity > 0) {
            int toRead = Math.min(readable, capacity);
            byte[] tmp = new byte[toRead];
            content.getBytes(content.readerIndex(), tmp);  // non-destructive read
            baos.write(tmp, 0, toRead);
        }
    }
    // Always pass downstream
    super.channelRead(ctx, msg);
    return;
}
```

**`ResponseHandler.write()` changes:**

On `HttpResponse` frame (existing, extended):
```java
// NEW: decide whether to accumulate response body
if (BodyCaptureConfig.enabled() && BodyCaptureConfig.captureResponse()) {
    String ct = response.headers().get("Content-Type");
    if (BodyCaptureConfig.isAllowedContentType(ct)) {
        ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).set(new ByteArrayOutputStream());
    }
}
```

On `HttpContent` frame (NEW — currently skipped by early return):
```java
if (msg instanceof HttpContent && !(msg instanceof LastHttpContent)) {
    ByteArrayOutputStream baos = ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).get();
    if (baos != null) {
        ByteBuf content = ((HttpContent) msg).content();
        int readable = content.readableBytes();
        int capacity = BodyCaptureConfig.maxBytes() - baos.size();
        if (capacity > 0) {
            int toRead = Math.min(readable, capacity);
            byte[] tmp = new byte[toRead];
            content.getBytes(content.readerIndex(), tmp);
            baos.write(tmp, 0, toRead);
        }
    }
    super.write(ctx, msg, promise);
    return;
}
```

**`endServerSpan()` changes** (where body attributes are set):

```java
private static void endServerSpan(ChannelHandlerContext ctx, Throwable error) {
    // ... existing span retrieval ...

    // NEW: attach body attributes
    if (BodyCaptureConfig.enabled() && span.isRecording()) {
        HttpResponse httpResponse = ctx.channel().attr(SERVER_RESPONSE_KEY).get();
        int statusCode = httpResponse != null ? httpResponse.status().code() : 0;
        boolean shouldAttach = !BodyCaptureConfig.errorOnly() || statusCode >= 400;

        if (shouldAttach) {
            ByteArrayOutputStream reqBytes = ctx.channel().attr(BODY_REQUEST_BYTES_KEY).get();
            ByteArrayOutputStream resBytes = ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).get();

            if (reqBytes != null && reqBytes.size() > 0) {
                String body = reqBytes.toString("UTF-8");
                // append [TRUNCATED] if we hit the limit
                if (reqBytes.size() >= BodyCaptureConfig.maxBytes()) body += "[TRUNCATED]";
                span.setAttribute("http.request.body", body);
            }
            if (resBytes != null && resBytes.size() > 0) {
                String body = resBytes.toString("UTF-8");
                if (resBytes.size() >= BodyCaptureConfig.maxBytes()) body += "[TRUNCATED]";
                span.setAttribute("http.response.body", body);
            }
        }
    }

    // Clear channel attributes
    ctx.channel().attr(BODY_REQUEST_ACTIVE_KEY).set(null);
    ctx.channel().attr(BODY_REQUEST_BYTES_KEY).set(null);
    ctx.channel().attr(BODY_RESPONSE_BYTES_KEY).set(null);

    // ... existing span.end() ...
}
```

### v4 agent — body-only Netty handler

v4 has no `NettyServerTracingHandler` (SERVER spans come from VertxTracer SPI). Add a new `NettyBodyCaptureHandler` to v4 agent:

- Same `NettyServerPipelineAdvice` hook (`DefaultChannelPipeline.addLast()`)
- `RequestHandler`: on `HttpRequest`, capture `Span.current()` (the SPI's span) into a channel attribute `last9.body.span`. Accumulate `HttpContent` bytes identically to v3.
- `ResponseHandler`: accumulate response `HttpContent` bytes identically to v3.
- On `LastHttpContent` (outbound): retrieve stored span, set `http.request.body` / `http.response.body` with same `errorOnly` logic.
- Does NOT create or end spans — lifecycle fully owned by VertxTracer SPI.

### Library path (v3 TracedRouter, v4 SpanNameUpdater)

Request body only (response body requires agent):

```java
if (BodyCaptureConfig.enabled() && BodyCaptureConfig.captureRequest()) {
    Buffer body = ctx.getBody();  // non-null only if BodyHandler present on route
    String path = ctx.request().path();
    String ct   = ctx.request().getHeader("content-type");
    if (body != null
            && BodyCaptureConfig.isAllowedContentType(ct)
            && BodyCaptureConfig.isAllowedPath(path)) {
        String s = body.toString("UTF-8");
        if (s.length() > BodyCaptureConfig.maxBytes()) {
            s = s.substring(0, BodyCaptureConfig.maxBytes()) + "[TRUNCATED]";
        }
        span.setAttribute("http.request.body", s);
    }
}
```

### Double-capture (agent + TracedRouter both active)

Both paths write the same value to `http.request.body`. OTel `Span.setAttribute()` overwrites silently — no error, no duplication in exported span. No deduplication logic needed.

### Cleanup of existing unstaged changes

Folded into implementation commits (not separate):
- v4 `SpanNameUpdater.java`: remove duplicate `isTextOrJson()` / `trunc()` methods
- v3 `TracedRouter.java`: update partial body capture to new `BodyCaptureConfig` API
- Both `BodyCaptureConfig.java`: replace stubs with full implementation

---

## Modified files

| File | Action |
|---|---|
| `v3/BodyCaptureConfig.java` | Rewrite — full config surface |
| `v4/BodyCaptureConfig.java` | Rewrite — full config surface |
| `v3/agent/NettyServerTracingHandler.java` | Extend — body accumulation in RequestHandler + ResponseHandler + endServerSpan |
| `v4/agent/NettyBodyCaptureHandler.java` | New — body-only Netty handler for v4 |
| `v4/agent/NettyBodyCapturePipelineAdvice.java` | New — pipeline injection advice for v4 |
| `v3/TracedRouter.java` | Update — use new BodyCaptureConfig API for library-path request body |
| `v4/SpanNameUpdater.java` | Update — fix duplicates, add library-path request body |
| `v3/agent/OtelLauncher.java` | Update — no new transformers needed (Netty advice already registered) |
| `v4/agent/OtelLauncher.java` | Update — register NettyBodyCapturePipelineAdvice |

---

## Data flow (agent path)

```
Incoming request
  ↓
NettyServerPipelineAdvice         ← ByteBuddy on DefaultChannelPipeline.addLast()
  → injects NettyServerTracingHandler into pipeline (already done)
  ↓
RequestHandler.channelRead(HttpRequest)
  → creates SERVER span (v3) / captures Span.current() (v4)
  → checks path + content-type → sets last9.body.request.active
  ↓
RequestHandler.channelRead(HttpContent)  ← NEW
  → if active: extract bytes from ByteBuf non-destructively → append to ByteArrayOutputStream
  → passes frame downstream unchanged
  ↓
ResponseHandler.write(HttpResponse)
  → sets status code on span
  → if content-type allowed: allocates last9.body.response.bytes ByteArrayOutputStream
  ↓
ResponseHandler.write(HttpContent)  ← NEW
  → appends response bytes to ByteArrayOutputStream
  ↓
ResponseHandler.write(LastHttpContent)
  → endServerSpan()
     → checks errorOnly + status code
     → if shouldAttach: reads both ByteArrayOutputStreams → span.setAttribute()
     → clears all channel attributes
     → span.end()
```

---

## Capability matrix

| | Request body | Response body |
|---|---|---|
| Agent v3 | Netty `HttpContent` inbound accumulation | Netty `HttpContent` outbound accumulation |
| Agent v4 | Netty `HttpContent` inbound (new handler) | Netty `HttpContent` outbound (new handler) |
| Library v3 TracedRouter | `ctx.getBody()` (requires BodyHandler) | Not supported |
| Library v4 SpanNameUpdater | `ctx.getBody()` (requires BodyHandler) | Not supported |

---

## Testing

### Unit tests
- `BodyCaptureConfigTest` — all env vars, defaults, deprecated alias, content-type/path helpers
- `NettyServerTracingHandlerBodyTest` — mock Netty pipeline, send `HttpRequest` + `HttpContent` + `LastHttpContent`, verify span attributes set correctly

### Integration tests (per module)
- `BodyCaptureRequestTest` — POST JSON body, assert `http.request.body` on span
- `BodyCaptureResponseTest` — agent path, GET with JSON response, assert `http.response.body`
- `BodyCaptureErrorOnlyTest` — `ERROR_ONLY=true`, body absent on 200, present on 500
- `BodyCapturePathFilterTest` — exclude `/health`, assert no body attr on that path
- `BodyCaptureSizeTest` — body > 8192 bytes → `[TRUNCATED]` suffix
- `BodyCaptureContentTypeTest` — `application/json` captured, `multipart/form-data` skipped

### E2E (docker-compose example app)
- Set `VERTX_OTEL_BODY_CAPTURE_ENABLED=true`
- POST JSON to route → verify `http.request.body` + `http.response.body` in collector output
- POST body > 8192 bytes → verify `[TRUNCATED]`
- Hit `/health` with `EXCLUDE_PATHS=/health` → verify no body attrs

---

## Open questions

1. **v4 `Span.current()` timing**: confirm the VertxTracer SPI span is current on the Netty event loop thread when `HttpRequest` arrives in `RequestHandler.channelRead()`. If not, need alternative span lookup (e.g., channel attribute set by the SPI).
2. **`HttpContent` vs `DefaultHttpContent`**: verify Vert.x 3.9.16 sends body as `DefaultHttpContent` (implements `HttpContent`) not raw `ByteBuf`. Confirm via debug log during E2E test.
