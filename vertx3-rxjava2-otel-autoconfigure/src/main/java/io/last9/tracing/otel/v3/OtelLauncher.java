package io.last9.tracing.otel.v3;

import io.last9.tracing.otel.OtelSdkSetup;
import io.last9.tracing.otel.v3.agent.Vertx3Instrumenter;
import io.vertx.core.Launcher;
import io.vertx.core.VertxOptions;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

/**
 * Custom Vert.x 3 Launcher that auto-configures OpenTelemetry and installs
 * ByteBuddy bytecode instrumentation for zero-code tracing.
 *
 * <p>This launcher extends {@link io.vertx.core.Launcher} and performs three steps
 * before Vert.x starts:
 * <ol>
 *   <li>Auto-configures the OpenTelemetry SDK from OTEL_* environment variables</li>
 *   <li>Installs RxJava2 context propagation hooks</li>
 *   <li>Self-attaches ByteBuddy and installs bytecode instrumentation for Router,
 *       WebClient, Kafka, Aerospike, Redis, JDBC, and reactive SQL</li>
 * </ol>
 *
 * <p>No {@code -javaagent} flag is needed — this launcher handles everything.
 * Applications only need to set this as the main class and use standard Vert.x APIs.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * <mainClass>io.last9.tracing.otel.v3.OtelLauncher</mainClass>
 * }</pre>
 *
 * @see Vertx3Instrumenter
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

            // 3. Install ByteBuddy bytecode instrumentation for zero-code tracing
            installByteBuddyInstrumentation();

        } catch (Exception e) {
            log.error("Failed to initialize OpenTelemetry: {}", e.getMessage(), e);
            log.warn("Application will start WITHOUT tracing.");
        }
    }

    private void installByteBuddyInstrumentation() {
        try {
            Instrumentation inst = ByteBuddyAgent.install();
            Vertx3Instrumenter.install(inst);
            log.info("ByteBuddy zero-code instrumentation installed via self-attach");
        } catch (Exception e) {
            log.warn("ByteBuddy self-attach failed: {}. " +
                    "Use -javaagent flag for bytecode instrumentation, " +
                    "or use TracedRouter.create(vertx) for manual tracing.", e.getMessage());
        }
    }
}
