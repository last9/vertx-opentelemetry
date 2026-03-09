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
 * Java agent entry point — zero-dependency, works out of the box.
 *
 * <p>This is the <b>only</b> class (along with {@link AgentClassLoader}) that the JVM
 * places on the system classloader when used as {@code -javaagent}. All heavy dependencies
 * (ByteBuddy, OTel SDK, instrumentation helpers) are embedded inside the agent JAR and
 * injected onto the system classloader at startup via
 * {@link Instrumentation#appendToSystemClassLoaderSearch(JarFile)}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * java -javaagent:vertx3-otel-agent-2.2.0.jar -jar app.jar
 * }</pre>
 *
 * <p>No additional Maven dependencies are required. The agent is fully self-contained.
 *
 * <h2>What happens at startup</h2>
 * <ol>
 *   <li>Extracts the embedded library JAR ({@code inst/agent-impl.jar}) to a temp file</li>
 *   <li>Injects it onto the system classloader via
 *       {@code Instrumentation.appendToSystemClassLoaderSearch()} — all helper classes,
 *       OTel SDK, and ByteBuddy become available to the application</li>
 *   <li>Stores the {@code Instrumentation} handle via {@code OtelAgent.storeInstrumentation()}</li>
 *   <li>Initializes the OTel SDK (registers {@code GlobalOpenTelemetry})</li>
 *   <li>Installs RxJava2 context propagation hooks</li>
 *   <li>Installs ByteBuddy class transformers for Router, WebClient, Aerospike, Kafka,
 *       Redis, JDBC, Reactive SQL, and RESTEasy</li>
 * </ol>
 *
 * <h2>Classloader architecture</h2>
 * <pre>
 * ┌─ Bootstrap CL ──────────────────────────────────────────┐
 * │  java.*, javax.*, JDK classes                           │
 * └────────────┬────────────────────────────────────────────┘
 *              │
 * ┌────────────▼────────────────────────────────────────────┐
 * │  System/App CL                                          │
 * │  - AgentBootstrap (2 classes from agent JAR)            │
 * │  - App code, Vert.x, RxJava                            │
 * │  - [injected] OTel SDK, ByteBuddy, helper classes       │
 * │    (from inst/agent-impl.jar via appendToSystemCL)      │
 * └─────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>ByteBuddy advice is <b>inlined</b> into target methods on the app classloader.
 * The inlined bytecode references helper classes (e.g., {@code AerospikeClientHelper})
 * which are resolved from the app classloader — where the injected library lives.
 *
 * <p>If the application already includes {@code vertx3-rxjava2-otel-autoconfigure} as a
 * compile dependency, the app's version takes precedence (system classloader searches
 * the original classpath before appended JARs). This avoids conflicts when both
 * the agent and the library dependency are present.
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
    private static final String OTEL_AGENT_CLASS =
            "io.last9.tracing.otel.OtelAgent";

    private AgentBootstrap() {}

    /**
     * Called by the JVM before the application's {@code main} method.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            // 1. Extract embedded library JAR and inject onto the system classloader.
            //    This makes OTel SDK, ByteBuddy, and all helper classes available to
            //    the application without requiring any compile dependencies.
            injectLibraryOntoSystemClassLoader(inst);

            // 2. Store instrumentation handle on the app classloader's OtelAgent
            storeInstrumentationOnAppClassLoader(inst);

            // 3. Initialize OTel SDK + RxJava hooks on the app classloader
            initializeOnAppClassLoader();

            // 4. Install ByteBuddy class transformers (loaded from system classloader)
            installTransformers(inst);

            log("[Last9 OTel Agent] Zero-code instrumentation installed successfully");
        } catch (Exception e) {
            log("[Last9 OTel Agent] Failed to install instrumentation: " + e.getMessage());
            e.printStackTrace(System.err);
            log("[Last9 OTel Agent] Application will start WITHOUT bytecode instrumentation.");
        }
    }

    /**
     * Called when this agent is attached dynamically.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    /**
     * Extracts the embedded library JAR and injects it onto the system classloader.
     *
     * <p>{@link Instrumentation#appendToSystemClassLoaderSearch(JarFile)} adds the JAR
     * to the end of the system classloader's search path. This means:
     * <ul>
     *   <li>If the app already has the library on its classpath, the app's version wins</li>
     *   <li>If the app does NOT have it, the agent's embedded version is found</li>
     * </ul>
     *
     * <p>This is the standard approach used by Java agents (e.g. OTel Java agent) to make
     * helper classes available to inlined ByteBuddy advice without requiring the application
     * to add compile dependencies.
     */
    private static void injectLibraryOntoSystemClassLoader(Instrumentation inst)
            throws Exception {
        URL agentJarUrl = AgentBootstrap.class.getProtectionDomain()
                .getCodeSource().getLocation();
        File agentJarFile = new File(agentJarUrl.toURI());

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

            // Inject onto system classloader — all classes become available to the app
            inst.appendToSystemClassLoaderSearch(new JarFile(tempJar.toFile()));
            log("[Last9 OTel Agent] Library classes injected onto system classloader");
        } finally {
            agentJar.close();
        }
    }

    /**
     * Stores the {@code Instrumentation} handle via {@code OtelAgent} on the app classloader.
     */
    private static void storeInstrumentationOnAppClassLoader(Instrumentation inst)
            throws Exception {
        ClassLoader appCL = ClassLoader.getSystemClassLoader();
        Class<?> otelAgent = appCL.loadClass(OTEL_AGENT_CLASS);
        otelAgent.getMethod("storeInstrumentation", Instrumentation.class).invoke(null, inst);
    }

    /**
     * Initialize OTel SDK and RxJava hooks on the application's classloader.
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
            // RxJava2 not on classpath — skip
        }
    }

    /**
     * Loads Vertx3Instrumenter from the system classloader and installs
     * ByteBuddy class transformers.
     */
    private static void installTransformers(Instrumentation inst)
            throws Exception {
        ClassLoader appCL = ClassLoader.getSystemClassLoader();
        Class<?> instrumenter = appCL.loadClass(INSTRUMENTER_CLASS);
        instrumenter.getMethod("installTransformersOnly", Instrumentation.class)
                .invoke(null, inst);
        log("[Last9 OTel Agent] ByteBuddy class transformers installed");
    }

    /**
     * Log to stderr (SLF4J may not be available yet).
     */
    private static void log(String message) {
        System.err.println(message);
    }
}
