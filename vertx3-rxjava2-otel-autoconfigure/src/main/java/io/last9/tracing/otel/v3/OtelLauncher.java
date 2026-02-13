package io.last9.tracing.otel.v3;

import io.last9.tracing.otel.OtelSdkSetup;
import io.vertx.core.Launcher;
import io.vertx.core.VertxOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom Vert.x 3 Launcher that auto-configures OpenTelemetry.
 *
 * <p>This launcher extends {@link io.vertx.core.Launcher} and configures OpenTelemetry
 * before Vert.x starts. Unlike Vert.x 4, there is no built-in tracing SPI, so
 * HTTP tracing is provided by {@link TracedRouter} (handler-based instrumentation).
 *
 * <p>This launcher provides:
 * <ul>
 *   <li>OpenTelemetry SDK auto-configuration from OTEL_* environment variables</li>
 *   <li>RxJava2 context propagation hooks</li>
 *   <li>Logback MDC injection for trace_id/span_id</li>
 * </ul>
 *
 * <p>For HTTP tracing, use {@link TracedRouter#create(io.vertx.reactivex.core.Vertx)}
 * instead of {@code Router.router(vertx)}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * <mainClass>io.last9.tracing.otel.v3.OtelLauncher</mainClass>
 * }</pre>
 *
 * @see TracedRouter
 * @see <a href="https://opentelemetry.io/docs/concepts/sdk-configuration/">OpenTelemetry SDK Configuration</a>
 */
public class OtelLauncher extends Launcher {

    private static final Logger log = LoggerFactory.getLogger(OtelLauncher.class);

    public static void main(String[] args) {
        new OtelLauncher().dispatch(args);
    }

    @Override
    public void beforeStartingVertx(VertxOptions options) {
        try {
            // 1. Auto-configure OpenTelemetry SDK (shared setup)
            OtelSdkSetup.initialize();
            log.info("OpenTelemetry SDK initialized for Vert.x 3");

            // 2. Install RxJava2 context propagation hooks
            RxJava2ContextPropagation.install();
            log.info("RxJava2 context propagation hooks installed");

            log.info("Use TracedRouter.create(vertx) for automatic HTTP tracing.");

        } catch (Exception e) {
            log.error("Failed to initialize OpenTelemetry: {}", e.getMessage(), e);
            log.warn("Application will start WITHOUT tracing.");
        }
    }
}
