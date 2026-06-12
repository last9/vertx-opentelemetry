# ADR 0001 — Body capture via Netty pipeline extension, not ByteBuddy on Vert.x impl classes

**Date**: 2026-05-23  
**Status**: Accepted

## Context

Adding HTTP request/response body capture to OTel spans. Two candidate approaches:

**Approach A (rejected)**: ByteBuddy advice targeting `io.vertx.core.http.impl.HttpServerRequestImpl.handler(Handler<Buffer>)` and `HttpServerResponseImpl.write(Buffer)/end(Buffer)`. Wraps the user's chunk handler to also accumulate bytes.

**Approach B (chosen)**: Extend the existing `NettyServerTracingHandler` — a `ChannelInboundHandlerAdapter` + `ChannelOutboundHandlerAdapter` already injected into the Netty pipeline by `NettyServerPipelineAdvice`. Add `HttpContent` frame handling to both the inbound handler (request bytes) and outbound handler (response bytes).

## Decision

Extend `NettyServerTracingHandler` (Approach B).

## Reasons

1. **Already there**: `NettyServerTracingHandler` is already in the pipeline for every HTTP connection. It sees all `HttpRequest`, `HttpResponse`, `HttpContent`, and `LastHttpContent` frames non-destructively — bytes flow downstream unchanged regardless of what we read.

2. **Non-invasive**: Netty pipeline handlers are additive. Reading `ByteBuf` bytes with `getBytes()` (vs `readBytes()`) does not advance the reader index — downstream handlers see the full content. No wrapping of user handlers needed.

3. **Fragility**: Approach A targets internal Vert.x impl classes (`*.impl.*`) that are not part of the public API and can be renamed or restructured across patch versions (Vert.x 3.9.x has had impl-class renames). Netty's `ChannelHandler` pipeline contract is stable.

4. **Span lifecycle integration**: `endServerSpan()` in `NettyServerTracingHandler` is the natural place to read accumulated bytes and set span attributes — it already has the span reference and fires after response status is known.

## Trade-offs

- Approach A would have been simpler to reason about at the Vert.x abstraction level.
- Approach B requires understanding Netty's `HttpContent` / `LastHttpContent` / `ByteBuf` lifecycle and reference counting. Response body capture had a latent bug in the existing `ResponseHandler` (early return skipped intermediate `HttpContent` frames) that had to be identified and fixed.
