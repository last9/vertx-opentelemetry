package io.last9.tracing.otel.v3.agent;

import io.last9.tracing.otel.v3.TracedWebClient;
import io.vertx.reactivex.ext.web.client.WebClient;

/**
 * Helper for {@link WebClientAdvice}. Separated to avoid loading WebClient/TracedWebClient
 * during ByteBuddy class transformation (which would cause LinkageError).
 */
public final class WebClientAdviceHelper {

    private WebClientAdviceHelper() {}

    /**
     * Wraps the given WebClient with TracedWebClient if not already wrapped.
     */
    public static Object maybeWrap(Object client) {
        if (client instanceof WebClient && !(client instanceof TracedWebClient)) {
            return TracedWebClient.wrap((WebClient) client);
        }
        return client;
    }
}
