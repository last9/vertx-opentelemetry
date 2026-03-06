package io.last9.tracing.otel.agent;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

/**
 * Java agent entry point with classloader isolation.
 *
 * <p>This is the <b>only</b> class (along with {@link AgentClassLoader}) that the JVM
 * places on the system classloader when used as {@code -javaagent}. All heavy dependencies
 * (ByteBuddy, OTel SDK, instrumentation code) are loaded from an embedded JAR via an
 * isolated classloader, preventing classpath conflicts with the application.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * java -javaagent:vertx3-otel-agent-2.1.0.jar -jar app.jar
 * }</pre>
 *
 * <p>The application must include {@code vertx3-rxjava2-otel-autoconfigure} as a Maven
 * dependency. The agent provides the JVM {@code Instrumentation} handle and installs
 * ByteBuddy class transformers. The actual tracing wrappers (TracedRouter, etc.) are
 * resolved from the application's classpath at runtime.
 *
 * <h2>What happens at startup</h2>
 * <ol>
 *   <li>Stores the {@code Instrumentation} handle via system property
 *       (accessible by {@code OtelLauncher} if also used)</li>
 *   <li>Initializes the OTel SDK on the application classloader</li>
 *   <li>Installs RxJava2 context propagation hooks on the application classloader</li>
 *   <li>Creates an isolated classloader from the embedded library JAR</li>
 *   <li>Loads ByteBuddy and installs class transformers from the isolated classloader</li>
 * </ol>
 *
 * <h2>Classloader architecture</h2>
 * <pre>
 * ┌─ Bootstrap CL ──────────────────────────────────────────┐
 * │  java.*, javax.*, JDK classes                           │
 * └────────────┬───────────────────────────────┬────────────┘
 *              │                               │
 * ┌────────────▼────────────┐   ┌──────────────▼───────────┐
 * │  System/App CL          │   │  AgentClassLoader         │
 * │  - AgentBootstrap (2)   │   │  (isolated, parent=boot)  │
 * │  - App code             │   │  - ByteBuddy              │
 * │  - Vert.x, RxJava      │   │  - Advice classes         │
 * │  - Our library (dep)    │   │  - Vertx3Instrumenter     │
 * │  - OTel SDK (dep)       │   │  - OTel SDK (copy)        │
 * └─────────────────────────┘   │  Falls back to System CL  │
 *                               │  for SLF4J, Vert.x, etc.  │
 *                               └───────────────────────────┘
 * </pre>
 *
 * <p>ByteBuddy advice is <b>inlined</b> into target methods on the app classloader.
 * The inlined bytecode references helper classes (e.g., {@code RouterAdviceHelper})
 * which are resolved from the app classloader — where our library lives as a dependency.
 *
 * @see AgentClassLoader
 */
public final class AgentBootstrap {

    private static final String EMBEDDED_JAR = "inst/agent-impl.jar";
    private static final String INSTRUMENTER_CLASS =
            "io.last9.tracing.otel.v3.agent.Vertx3Instrumenter";
    private static final String SDK_SETUP_CLASS =
            "io.last9.tracing.otel.OtelSdkSetup";
    private static final String RX_PROPAGATION_CLASS =
            "io.last9.tracing.otel.v3.RxJava2ContextPropagation";

    /** System property key for cross-classloader Instrumentation sharing. */
    static final String INSTRUMENTATION_PROPERTY = "io.last9.otel.instrumentation";

    private AgentBootstrap() {}

    /**
     * Called by the JVM before the application's {@code main} method.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        // Store instrumentation handle (cross-classloader via system property)
        System.getProperties().put(INSTRUMENTATION_PROPERTY, inst);

        try {
            // 1. Initialize OTel SDK + RxJava hooks on the app classloader
            initializeOnAppClassLoader();

            // 2. Create isolated classloader and install ByteBuddy transformers
            ClassLoader isolated = createIsolatedClassLoader();
            installTransformers(isolated, inst);

            log("[Last9 OTel Agent] Zero-code instrumentation installed successfully");
        } catch (Exception e) {
            log("[Last9 OTel Agent] Failed to install instrumentation: " + e.getMessage());
            e.printStackTrace(System.err);
            log("[Last9 OTel Agent] Application will start WITHOUT bytecode instrumentation. " +
                    "Use OtelLauncher as main class for self-attach fallback.");
        }
    }

    /**
     * Called when this agent is attached dynamically.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    /**
     * Initialize OTel SDK and RxJava hooks on the application's classloader.
     *
     * <p>Uses reflection via the system classloader to ensure the SDK is registered
     * on the app's {@code GlobalOpenTelemetry} — the one that TracedRouter, TracedWebClient,
     * and other library classes will use at runtime.
     */
    private static void initializeOnAppClassLoader() throws Exception {
        ClassLoader appCL = ClassLoader.getSystemClassLoader();

        // Initialize OTel SDK
        Class<?> sdkSetup = appCL.loadClass(SDK_SETUP_CLASS);
        sdkSetup.getMethod("initialize").invoke(null);
        log("[Last9 OTel Agent] OpenTelemetry SDK initialized");

        // Install RxJava2 context propagation hooks
        try {
            Class<?> rxPropagation = appCL.loadClass(RX_PROPAGATION_CLASS);
            rxPropagation.getMethod("install").invoke(null);
            log("[Last9 OTel Agent] RxJava2 context propagation installed");
        } catch (ClassNotFoundException e) {
            // RxJava2 not on classpath — skip (might be v4 module or no RxJava)
        }
    }

    /**
     * Creates an isolated classloader from the embedded library JAR.
     *
     * <p>The embedded JAR is extracted to a temp file and loaded by {@link AgentClassLoader}
     * with parent = bootstrap classloader. This ensures ByteBuddy and our instrumentation
     * classes don't leak onto the system classloader.
     */
    private static ClassLoader createIsolatedClassLoader() throws Exception {
        URL agentJarUrl = AgentBootstrap.class.getProtectionDomain()
                .getCodeSource().getLocation();
        File agentJarFile = new File(agentJarUrl.toURI());

        // Extract embedded JAR to temp file
        JarFile agentJar = new JarFile(agentJarFile);
        try {
            InputStream embeddedStream = agentJar.getInputStream(
                    agentJar.getJarEntry(EMBEDDED_JAR));

            if (embeddedStream == null) {
                throw new IllegalStateException(
                        "Embedded JAR not found in agent: " + EMBEDDED_JAR);
            }

            Path tempJar = Files.createTempFile("last9-otel-agent-", ".jar");
            tempJar.toFile().deleteOnExit();
            Files.copy(embeddedStream, tempJar, StandardCopyOption.REPLACE_EXISTING);
            embeddedStream.close();

            return new AgentClassLoader(
                    new URL[]{tempJar.toUri().toURL()},
                    ClassLoader.getSystemClassLoader()
            );
        } finally {
            agentJar.close();
        }
    }

    /**
     * Loads Vertx3Instrumenter from the isolated classloader and installs
     * ByteBuddy class transformers.
     *
     * <p>Only the transformer installation runs here — OTel SDK and RxJava hooks
     * were already initialized on the app classloader.
     */
    private static void installTransformers(ClassLoader isolated, Instrumentation inst)
            throws Exception {
        Class<?> instrumenter = isolated.loadClass(INSTRUMENTER_CLASS);
        instrumenter.getMethod("installTransformersOnly", Instrumentation.class)
                .invoke(null, inst);
        log("[Last9 OTel Agent] ByteBuddy class transformers installed");
    }

    /**
     * Log to stderr (SLF4J may not be available on the system classloader).
     */
    private static void log(String message) {
        System.err.println(message);
    }
}
