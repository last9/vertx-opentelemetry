package io.last9.tracing.otel.v4.agent;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice that intercepts {@code VertxBuilder.init()} to inject
 * {@code TracingOptions} and {@code MicrometerMetricsOptions} into the
 * {@code VertxOptions} before the Vert.x instance is created.
 *
 * <p>This enables the Vert.x 4 {@code VertxTracer} SPI for automatic
 * HTTP server/client, EventBus, SQL, Redis, and Kafka tracing — without
 * any code changes in the application.
 */
public class VertxFactoryAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object builder) {
        VertxFactoryHelper.injectOptions(builder);
    }
}
