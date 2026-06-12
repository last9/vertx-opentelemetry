package io.last9.tracing.otel.v3.agent;

import io.last9.tracing.otel.v3.BodyCaptureConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for body capture in {@link NettyServerTracingHandler}.
 * Uses {@link EmbeddedChannel} to drive HTTP frames without a real network.
 */
class NettyServerTracingHandlerBodyTest {

    private static final AttributeKey<String> ATTR_REQ_BODY =
            AttributeKey.stringKey("http.request.body");
    private static final AttributeKey<String> ATTR_RESP_BODY =
            AttributeKey.stringKey("http.response.body");

    private GlobalOtelTestSetup otel;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void setUp() {
        NettyServerTracingHandler.resetForTest();
        otel = new GlobalOtelTestSetup();
        otel.setUp();
        spanExporter = otel.getSpanExporter();
        // Enable body capture for all tests by default
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            return null;
        };
    }

    @AfterEach
    void tearDown() {
        otel.tearDown();
        BodyCaptureConfig.envProvider = System::getenv;
    }

    private EmbeddedChannel newChannel() {
        return new EmbeddedChannel(
                new NettyServerTracingHandler.RequestHandler(),
                new NettyServerTracingHandler.ResponseHandler());
    }

    private static DefaultFullHttpRequest jsonPost(String uri, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                HTTP_1_1, HttpMethod.POST, uri, Unpooled.copiedBuffer(bytes));
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        req.headers().set(HttpHeaderNames.HOST, "localhost");
        return req;
    }

    private static DefaultFullHttpRequest getRequest(String uri) {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                HTTP_1_1, HttpMethod.GET, uri, Unpooled.EMPTY_BUFFER);
        req.headers().set(HttpHeaderNames.HOST, "localhost");
        return req;
    }

    private static DefaultFullHttpResponse jsonResponse(HttpResponseStatus status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                HTTP_1_1, status, Unpooled.copiedBuffer(bytes));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        return resp;
    }

    private static DefaultFullHttpResponse emptyResponse(HttpResponseStatus status) {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        return resp;
    }

    @Test
    void requestBodyCapturedForJsonPost() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(jsonPost("/api/data", "{\"key\":\"value\"}"));
        channel.writeOutbound(emptyResponse(HttpResponseStatus.OK));
        channel.finish();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(ATTR_REQ_BODY))
                .isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void responseBodyCapturedForJsonResponse() {
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(getRequest("/api/data"));
        channel.writeOutbound(jsonResponse(HttpResponseStatus.OK, "{\"result\":\"ok\"}"));
        channel.finish();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(ATTR_RESP_BODY))
                .isEqualTo("{\"result\":\"ok\"}");
    }

    @Test
    void noBodiesCapturedWhenDisabled() {
        BodyCaptureConfig.envProvider = key -> null;
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(jsonPost("/api/data", "{\"key\":\"value\"}"));
        channel.writeOutbound(jsonResponse(HttpResponseStatus.OK, "{\"result\":\"ok\"}"));
        channel.finish();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(ATTR_REQ_BODY)).isNull();
        assertThat(spans.get(0).getAttributes().get(ATTR_RESP_BODY)).isNull();
    }

    @Test
    void requestBodyTruncatedAtMaxBytes() {
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            if (BodyCaptureConfig.ENV_MAX_BYTES.equals(key)) return "10";
            return null;
        };
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(jsonPost("/api/data", "0123456789ABCDEF")); // 16 bytes > 10
        channel.writeOutbound(emptyResponse(HttpResponseStatus.OK));
        channel.finish();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(ATTR_REQ_BODY))
                .isEqualTo("0123456789[TRUNCATED]");
    }

    @Test
    void requestBodySkippedForUnsupportedContentType() {
        EmbeddedChannel channel = newChannel();
        byte[] body = "field=value".getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                HTTP_1_1, HttpMethod.POST, "/api/data", Unpooled.copiedBuffer(body));
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded");
        req.headers().set(HttpHeaderNames.HOST, "localhost");
        channel.writeInbound(req);
        channel.writeOutbound(emptyResponse(HttpResponseStatus.OK));
        channel.finish();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(ATTR_REQ_BODY)).isNull();
    }

    @Test
    void errorOnlySkipsBodyOn200() {
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            if (BodyCaptureConfig.ENV_ERROR_ONLY.equals(key)) return "true";
            return null;
        };
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(jsonPost("/api/data", "{\"key\":\"value\"}"));
        channel.writeOutbound(jsonResponse(HttpResponseStatus.OK, "{\"result\":\"ok\"}"));
        channel.finish();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(ATTR_REQ_BODY)).isNull();
        assertThat(spans.get(0).getAttributes().get(ATTR_RESP_BODY)).isNull();
    }

    @Test
    void errorOnlyAttachesBodyOn5xx() {
        BodyCaptureConfig.envProvider = key -> {
            if (BodyCaptureConfig.ENV_ENABLED.equals(key)) return "true";
            if (BodyCaptureConfig.ENV_ERROR_ONLY.equals(key)) return "true";
            return null;
        };
        EmbeddedChannel channel = newChannel();
        channel.writeInbound(jsonPost("/api/data", "{\"key\":\"value\"}"));
        channel.writeOutbound(jsonResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"oops\"}"));
        channel.finish();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(ATTR_REQ_BODY))
                .isEqualTo("{\"key\":\"value\"}");
        assertThat(spans.get(0).getAttributes().get(ATTR_RESP_BODY))
                .isEqualTo("{\"error\":\"oops\"}");
    }
}
