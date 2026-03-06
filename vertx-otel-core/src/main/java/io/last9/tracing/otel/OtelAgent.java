package io.last9.tracing.otel;

import java.lang.instrument.Instrumentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java agent entry point for zero-touch OpenTelemetry initialization.
 *
 * <p>Attach this agent with {@code -javaagent:/path/to/vertx4-rxjava3-otel-autoconfigure-2.0.0.jar}
 * (or the v3 equivalent). The JVM invokes {@link #premain} before the application's
 * {@code main} method, ensuring the OTel SDK is initialized before any Vert.x code runs.
 *
 * <p>If the application also calls {@link OtelSdkSetup#initialize()} programmatically
 * (e.g. via {@code OtelLauncher}), the second call is a no-op that returns the cached instance.
 *
 * <h2>Vert.x 4 — zero-code instrumentation</h2>
 * <p>Because Vert.x 4 discovers tracers via the {@code VertxTracer} SPI
 * ({@code META-INF/services}), adding this JAR as a {@code -javaagent} is sufficient for
 * full HTTP tracing with no application code changes. No bytecode manipulation is needed.
 *
 * <h2>Vert.x 3 — zero-code instrumentation via ByteBuddy</h2>
 * <p>Vert.x 3 has no {@code VertxTracer} SPI. When the Vert.x 3 fat JAR is used as
 * a {@code -javaagent}, this agent automatically installs ByteBuddy class transformers
 * that intercept {@code Router.router(Vertx)} and {@code WebClient.create(Vertx)} to
 * add tracing without any application code changes. RxJava2 context propagation hooks
 * are also installed automatically.
 *
 * <p>Database, Kafka, Redis, and Aerospike clients still require manual wrapping
 * with the {@code Traced*} classes because they need domain-specific parameters.
 */
public final class OtelAgent {

    private static final Logger log = LoggerFactory.getLogger(OtelAgent.class);

    /** FQCN of the Vert.x 3 bytecode instrumenter (present only in the v3 fat JAR). */
    private static final String VERTX3_INSTRUMENTER =
            "io.last9.tracing.otel.v3.agent.Vertx3Instrumenter";

    private OtelAgent() {}

    /**
     * Invoked by the JVM before the application main class when this JAR is used as a
     * {@code -javaagent}.
     *
     * <p>Performs two steps:
     * <ol>
     *   <li>Initializes the OpenTelemetry SDK</li>
     *   <li>If the Vert.x 3 instrumenter is on the classpath (i.e., this is the v3 fat JAR),
     *       installs ByteBuddy class transformers for zero-code HTTP tracing</li>
     * </ol>
     *
     * @param agentArgs command-line args after the {@code =} in {@code -javaagent:jar=args}
     * @param inst      the Instrumentation handle (passed to ByteBuddy for v3 instrumentation)
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("OtelAgent: initializing OpenTelemetry before application startup");
        try {
            OtelSdkSetup.initialize();
        } catch (Exception e) {
            // Never abort the JVM launch — log and continue untraced rather than failing.
            log.error("OtelAgent: OpenTelemetry initialization failed — "
                    + "application will start WITHOUT tracing: {}", e.getMessage(), e);
        }

        // Vert.x 3: install bytecode instrumentation for Router + WebClient.
        // In the v4 fat JAR the class is absent and this block is silently skipped.
        try {
            Class<?> instrumenter = Class.forName(VERTX3_INSTRUMENTER);
            instrumenter.getMethod("install", Instrumentation.class).invoke(null, inst);
        } catch (ClassNotFoundException e) {
            log.debug("OtelAgent: Vertx3Instrumenter not on classpath — "
                    + "skipping Vert.x 3 bytecode instrumentation");
        } catch (Exception e) {
            log.warn("OtelAgent: failed to install Vert.x 3 bytecode instrumentation: {}",
                    e.getMessage(), e);
        }
    }
}
