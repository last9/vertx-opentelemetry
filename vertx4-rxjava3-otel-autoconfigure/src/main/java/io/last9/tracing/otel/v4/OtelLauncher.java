package io.last9.tracing.otel.v4;

import io.last9.tracing.otel.OtelSdkSetup;
import io.opentelemetry.api.OpenTelemetry;
import io.vertx.core.Launcher;
import io.vertx.core.VertxOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom Vert.x 4 Launcher that auto-configures OpenTelemetry.
 *
 * <p>This launcher extends {@link io.vertx.core.Launcher} and configures OpenTelemetry
 * before Vert.x starts. It provides:
 * <ul>
 *   <li>OpenTelemetry SDK auto-configuration from OTEL_* environment variables</li>
 *   <li>Vert.x tracing integration for HTTP server/client, EventBus, SQL client</li>
 *   <li>RxJava3 context propagation hooks</li>
 *   <li>Logback MDC injection for trace_id/span_id</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * <mainClass>io.last9.tracing.otel.v4.OtelLauncher</mainClass>
 * }</pre>
 *
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
            OpenTelemetry openTelemetry = OtelSdkSetup.initialize();

            // 2. Configure Vert.x to use OpenTelemetry tracing via vertx-opentelemetry
            options.setTracingOptions(new OpenTelemetryOptions(openTelemetry));
            log.info("Vert.x 4 tracing configured (HTTP server/client, EventBus, SQL client)");

            // 3. Install RxJava3 context propagation hooks
            RxJava3ContextPropagation.install();
            log.info("RxJava3 context propagation hooks installed");

            log.info("All HTTP requests, DB queries, and EventBus messages will be traced automatically.");

        } catch (Exception e) {
            log.error("Failed to initialize OpenTelemetry: {}", e.getMessage(), e);
            log.warn("Application will start WITHOUT tracing.");
        }
    }
}
