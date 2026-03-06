package io.last9.tracing.otel.v3;

import io.last9.tracing.otel.OtelAgent;
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
 *   <li>Installs ByteBuddy bytecode instrumentation for Router, WebClient, Kafka,
 *       Aerospike, Redis, JDBC, and reactive SQL</li>
 * </ol>
 *
 * <h2>Instrumentation handle resolution</h2>
 * <p>ByteBuddy needs a JVM {@code Instrumentation} handle. This launcher resolves it
 * in priority order:
 * <ol>
 *   <li><b>From {@code -javaagent}</b>: If the JAR was loaded as a Java agent,
 *       {@link OtelAgent#getInstrumentation()} returns the handle from {@code premain}.
 *       No self-attach needed — works on JRE.</li>
 *   <li><b>Self-attach via ByteBuddy</b>: If no agent was used, attempts
 *       {@link ByteBuddyAgent#install()} which requires a JDK (Attach API).</li>
 *   <li><b>Graceful fallback</b>: If both fail, the app starts without bytecode
 *       instrumentation. Manual {@code Traced*} wrappers still work.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * <mainClass>io.last9.tracing.otel.v3.OtelLauncher</mainClass>
 * }</pre>
 *
 * @see OtelAgent
 * @see Vertx3Instrumenter
 */
public class OtelLauncher extends Launcher {

    private static final Logger log = LoggerFactory.getLogger(OtelLauncher.class);

    public static void main(String[] args) {
        new OtelLauncher().dispatch(args);
    }

    @Override
    public void beforeStartingVertx(VertxOptions options) {
        // If OtelAgent.premain() already ran (via -javaagent), SDK is initialized
        // and ByteBuddy transformers are installed. Just ensure RxJava2 hooks are in place.
        if (OtelAgent.getInstrumentation() != null) {
            log.info("OtelAgent already initialized via -javaagent — skipping SDK setup");
            RxJava2ContextPropagation.install();
            return;
        }

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
                    "Use -javaagent:app.jar flag for bytecode instrumentation, " +
                    "or use TracedRouter.create(vertx) for manual tracing.", e.getMessage());
        }
    }
}
