package io.last9.tracing.otel.v3.agent;

import io.last9.tracing.otel.v3.TestOtelSetup;
import io.last9.tracing.otel.v3.TracedWebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WebClientAdviceHelper#maybeWrap}.
 *
 * <p>{@code maybeWrap} is used in library/manual mode only — the agent no longer intercepts
 * {@code WebClient.create()} because doing so changes {@code object.getClass().getName()},
 * which breaks key-by-class patterns like {@code ContextUtils.setInstance()}. CLIENT spans
 * and {@code traceparent} injection are handled by {@link NettyHttpClientHelper} at the
 * Netty level instead.
 *
 * <p>These tests run without the ByteBuddy agent — they exercise {@code maybeWrap} directly.
 */
@ExtendWith(VertxExtension.class)
class WebClientAdviceHelperTest {

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
    void maybeWrapReturnsTracedWebClientForPlainWebClient(Vertx vertx) {
        WebClient client = WebClient.create(vertx);

        Object result = WebClientAdviceHelper.maybeWrap(client);

        assertThat(result)
                .as("maybeWrap must never return null for a non-null WebClient — "
                        + "null would break any DI container binding that stores the result")
                .isNotNull();
        assertThat(result).isInstanceOf(TracedWebClient.class);
    }

    @Test
    void maybeWrapDoesNotDoubleWrapTracedWebClient(Vertx vertx) {
        WebClient client = WebClient.create(vertx);
        TracedWebClient already = TracedWebClient.wrap(client, otel.getOpenTelemetry());

        Object result = WebClientAdviceHelper.maybeWrap(already);

        assertThat(result)
                .as("maybeWrap must return the same TracedWebClient unchanged — no double wrapping")
                .isSameAs(already);
    }

    @Test
    void maybeWrapPassesThroughNonWebClientObjects() {
        Object notAClient = "not-a-web-client";

        Object result = WebClientAdviceHelper.maybeWrap(notAClient);

        assertThat(result).isSameAs(notAClient);
    }

    @Test
    void maybeWrapPassesThroughNull() {
        Object result = WebClientAdviceHelper.maybeWrap(null);

        assertThat(result).isNull();
    }
}
