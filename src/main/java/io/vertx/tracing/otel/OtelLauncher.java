package io.vertx.tracing.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.vertx.core.Launcher;
import io.vertx.core.VertxOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom Vert.x Launcher that auto-configures OpenTelemetry.
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
 * <p>Use this class as your main class instead of {@code io.vertx.core.Launcher}:</p>
 * <pre>{@code
 * java -jar app.jar run com.example.MainVerticle
 * }</pre>
 *
 * <p>Or set it as the Main-Class in your JAR manifest via maven-shade-plugin:</p>
 * <pre>{@code
 * <mainClass>io.vertx.tracing.otel.OtelLauncher</mainClass>
 * }</pre>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>OTEL_SERVICE_NAME - Service name (required)</li>
 *   <li>OTEL_EXPORTER_OTLP_ENDPOINT - OTLP endpoint URL</li>
 *   <li>OTEL_EXPORTER_OTLP_HEADERS - Auth headers (optional)</li>
 *   <li>OTEL_RESOURCE_ATTRIBUTES - Additional resource attributes</li>
 *   <li>OTEL_LOGS_EXPORTER - Log exporter (otlp/none)</li>
 * </ul>
 *
 * @see <a href="https://opentelemetry.io/docs/concepts/sdk-configuration/">OpenTelemetry SDK Configuration</a>
 */
public class OtelLauncher extends Launcher {

    private static final Logger log = LoggerFactory.getLogger(OtelLauncher.class);

    /**
     * Main entry point. Use this instead of {@code io.vertx.core.Launcher}.
     *
     * @param args command line arguments (passed to Vert.x Launcher)
     */
    public static void main(String[] args) {
        new OtelLauncher().dispatch(args);
    }

    /**
     * Called before Vert.x starts. Configures OpenTelemetry and installs hooks.
     *
     * @param options the Vert.x options to configure
     */
    @Override
    public void beforeStartingVertx(VertxOptions options) {
        String serviceName = getEnvOrDefault("OTEL_SERVICE_NAME", "unknown-service");
        String otlpEndpoint = getEnvOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318");

        log.info("=== OpenTelemetry Auto-Configuration ===");
        log.info("Service: {}", serviceName);
        log.info("OTLP Endpoint: {}", otlpEndpoint);

        try {
            // 1. Auto-configure OpenTelemetry SDK from OTEL_* environment variables
            OpenTelemetry openTelemetry = AutoConfiguredOpenTelemetrySdk.builder()
                    .setResultAsGlobal()  // Make available via GlobalOpenTelemetry
                    .build()
                    .getOpenTelemetrySdk();

            log.info("OpenTelemetry SDK initialized successfully");

            // 2. Configure Vert.x to use OpenTelemetry tracing
            options.setTracingOptions(new OpenTelemetryOptions(openTelemetry));

            log.info("Vert.x tracing configured (HTTP server/client, EventBus, SQL client)");

            // 3. Install RxJava3 context propagation hooks
            RxJava3ContextPropagation.install();
            log.info("RxJava3 context propagation hooks installed");

            // 4. Install OpenTelemetry Logback appender for log export
            OpenTelemetryAppender.install(openTelemetry);
            log.info("Logback OpenTelemetry appender installed for log export");

            log.info("=== OpenTelemetry Ready ===");
            log.info("All HTTP requests, DB queries, and EventBus messages will be traced automatically.");

        } catch (Exception e) {
            log.error("Failed to initialize OpenTelemetry: {}", e.getMessage(), e);
            log.warn("Application will start WITHOUT tracing.");
        }
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
