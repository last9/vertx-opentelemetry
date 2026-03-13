package io.last9.tracing.otel;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java agent entry point for zero-touch OpenTelemetry initialization.
 *
 * <h2>Usage Option 1: Library JAR as agent (separate from app)</h2>
 * <pre>{@code
 * java -javaagent:vertx3-rxjava2-otel-autoconfigure-2.1.0.jar -jar app.jar run com.example.MainVerticle
 * }</pre>
 *
 * <h2>Usage Option 2: App fat JAR as its own agent (recommended)</h2>
 * <p>When the app's fat JAR includes this library and carries {@code Premain-Class} in its
 * manifest, a single JAR serves as both agent and application — no classpath conflicts:
 * <pre>{@code
 * java -javaagent:app.jar -jar app.jar run com.example.MainVerticle
 * }</pre>
 * <p>Add to your shade plugin's {@code ManifestResourceTransformer}:
 * <pre>{@code
 * <manifestEntries>
 *     <Premain-Class>io.last9.tracing.otel.OtelAgent</Premain-Class>
 *     <Can-Redefine-Classes>true</Can-Redefine-Classes>
 *     <Can-Retransform-Classes>true</Can-Retransform-Classes>
 * </manifestEntries>
 * }</pre>
 *
 * <h2>How it works</h2>
 * <p>The JVM invokes {@link #premain} before the application's {@code main} method.
 * The {@code Instrumentation} handle is stored so that {@code OtelLauncher} (if used)
 * can reuse it instead of attempting ByteBuddy self-attach.
 *
 * <h2>Vert.x 4</h2>
 * <p>Vert.x 4 discovers tracers via the {@code VertxTracer} SPI, so adding this JAR
 * as a {@code -javaagent} is sufficient for full HTTP tracing with no code changes.
 *
 * <h2>Vert.x 3</h2>
 * <p>Vert.x 3 has no {@code VertxTracer} SPI. This agent installs ByteBuddy class
 * transformers that intercept {@code Router.router(Vertx)}, {@code WebClient.create(Vertx)},
 * Kafka, Aerospike, Redis, JDBC, and reactive SQL — all without application code changes.
 *
 * @see OtelSdkSetup
 */
public final class OtelAgent {

    /** FQCN of the Vert.x 3 bytecode instrumenter (present only in the v3 fat JAR). */
    private static final String VERTX3_INSTRUMENTER =
            "io.last9.tracing.otel.v3.agent.Vertx3Instrumenter";

    /**
     * Stores the Instrumentation handle from premain/agentmain so that OtelLauncher
     * can reuse it without attempting ByteBuddy self-attach.
     */
    private static final AtomicReference<Instrumentation> INSTRUMENTATION = new AtomicReference<>();

    private OtelAgent() {}

    /**
     * Returns the {@code Instrumentation} handle if this agent was loaded via
     * {@code -javaagent}, or {@code null} if the agent was not used.
     *
     * <p>This allows {@code OtelLauncher} to detect that the agent already ran
     * and skip ByteBuddy self-attach.
     */
    public static Instrumentation getInstrumentation() {
        return INSTRUMENTATION.get();
    }

    /**
     * Stores the {@code Instrumentation} handle from an external agent (e.g., the
     * standalone {@code vertx3-otel-agent}).
     *
     * <p>Called via reflection by {@code AgentBootstrap} to pass the Instrumentation
     * handle across classloader boundaries without polluting {@code System.getProperties()}
     * with non-String values.
     */
    public static void storeInstrumentation(Instrumentation inst) {
        INSTRUMENTATION.set(inst);
    }

    /**
     * Invoked by the JVM before the application main class when this JAR is used as a
     * {@code -javaagent}.
     *
     * @param agentArgs command-line args after the {@code =} in {@code -javaagent:jar=args}
     * @param inst      the Instrumentation handle from the JVM
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        INSTRUMENTATION.set(inst);
        log("[Last9 OTel Agent] Initializing OpenTelemetry before application startup");
        initialize(inst);
    }

    /**
     * Invoked when this agent is attached dynamically (e.g. via the Attach API).
     *
     * @param agentArgs agent arguments
     * @param inst      the Instrumentation handle from the JVM
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        INSTRUMENTATION.set(inst);
        log("[Last9 OTel Agent] Attached dynamically — initializing OpenTelemetry");
        initialize(inst);
    }

    private static void initialize(Instrumentation inst) {
        try {
            OtelSdkSetup.initialize();
        } catch (Exception e) {
            log("[Last9 OTel Agent] OpenTelemetry initialization failed — "
                    + "application will start WITHOUT tracing: " + e.getMessage());
            e.printStackTrace(System.err);
        }

        // Vert.x 3: install bytecode instrumentation for Router, WebClient, Kafka, etc.
        // In the v4 fat JAR the class is absent and this block is silently skipped.
        try {
            Class<?> instrumenter = Class.forName(VERTX3_INSTRUMENTER);
            instrumenter.getMethod("install", Instrumentation.class).invoke(null, inst);
        } catch (ClassNotFoundException e) {
            // Vertx3Instrumenter not on classpath — skip Vert.x 3 bytecode instrumentation
        } catch (Exception e) {
            log("[Last9 OTel Agent] Failed to install Vert.x 3 bytecode instrumentation: "
                    + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private static void log(String message) {
        System.err.println(message);
    }
}
