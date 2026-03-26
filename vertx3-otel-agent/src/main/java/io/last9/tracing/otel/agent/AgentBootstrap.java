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
 *   <li>Installs ByteBuddy class transformers for Router, WebClient, Aerospike, Kafka,
 *       Redis, JDBC, Reactive SQL, and RESTEasy — <b>before</b> OTel SDK init to
 *       ensure transformers are registered before any application classes are loaded</li>
 *   <li>Initializes the OTel SDK (registers {@code GlobalOpenTelemetry})</li>
 *   <li>Installs RxJava2 context propagation hooks</li>
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

            // 3. Install ByteBuddy class transformers FIRST — before OTel SDK init.
            //    OTel SDK initialization (step 4) triggers class loading of Logback,
            //    SPI providers, and other libraries. If any of those transitively load
            //    application classes (e.g. Aerospike, Kafka clients), the transformers
            //    must already be registered to instrument them. RETRANSFORMATION can
            //    handle already-loaded classes, but may fail silently for some class
            //    structures. Installing transformers first avoids the issue entirely.
            installTransformers(inst);

            // 4. Initialize OTel SDK + RxJava hooks on the app classloader
            initializeOnAppClassLoader();

            // 5. Post-hoc diagnostic: detect classpath conflicts that may have already
            //    broken initialization. Cannot prevent the issue, only make it visible.
            checkClasspathConflicts();

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
     * Detects if the application's classpath contains an older version of
     * vertx3-rxjava2-otel-autoconfigure that would shadow the agent's shaded classes.
     *
     * <p>When both the library (as a Maven dependency) and the agent are present,
     * {@code appendToSystemClassLoaderSearch} puts the agent's classes LAST. The app's
     * older/unshaded classes win the race, causing:
     * <ul>
     *   <li>Helpers that reference unshaded {@code io.opentelemetry.*} while the SDK
     *       was initialized on shaded {@code io.last9.internal.otel.*}</li>
     *   <li>Stale helper code that still uses {@code SemanticAttributes} constants</li>
     * </ul>
     */
    private static void checkClasspathConflicts() {
        try {
            ClassLoader appCL = ClassLoader.getSystemClassLoader();

            // Check if the helper class exists and has the version marker
            Class<?> helper = appCL.loadClass(
                    "io.last9.tracing.otel.v3.agent.HttpServerAdviceHelper");

            // Check where the class was loaded from
            java.security.CodeSource cs = helper.getProtectionDomain().getCodeSource();
            String source = cs != null ? cs.getLocation().toString() : "unknown";
            log("[Last9 OTel Agent] HttpServerAdviceHelper loaded from: " + source);

            // Check for version marker (added in beta.8)
            try {
                String version = (String) helper.getField("HELPER_VERSION").get(null);
                log("[Last9 OTel Agent] HttpServerAdviceHelper version: " + version);
            } catch (NoSuchFieldException e) {
                // No HELPER_VERSION field — this is a pre-beta.8 class from the app's classpath
                log("[Last9 OTel Agent] WARNING: HttpServerAdviceHelper is missing version marker — "
                        + "loaded from app's classpath (not the agent). This means the app bundles an "
                        + "older version of io.last9:vertx3-rxjava2-otel-autoconfigure that shadows "
                        + "the agent's classes. SERVER spans may not work correctly. "
                        + "Fix: remove the io.last9:vertx3-rxjava2-otel-autoconfigure dependency from "
                        + "your pom.xml — the agent is fully self-contained.");
            }

            // Check if the unshaded GlobalOpenTelemetry class exists on the classpath.
            // Use getResource() instead of loadClass() to avoid triggering static initializers
            // which could interact with the already-initialized shaded SDK.
            boolean unshadedOtelPresent = appCL.getResource(
                    "io/opentelemetry/api/GlobalOpenTelemetry.class") != null;
            if (unshadedOtelPresent) {
                log("[Last9 OTel Agent] NOTE: Unshaded io.opentelemetry.api.GlobalOpenTelemetry found on classpath. "
                        + "If the app bundles its own OTel SDK, the agent's shaded SDK is isolated and "
                        + "will not conflict. However, if the app also bundles vertx3-rxjava2-otel-autoconfigure "
                        + "(pre-beta.7), helpers will use the wrong GlobalOpenTelemetry.");
            }
        } catch (Throwable t) {
            log("[Last9 OTel Agent] Classpath check failed: " + t.getMessage());
        }
    }

    /**
     * Log to stderr (SLF4J may not be available yet).
     */
    private static void log(String message) {
        System.err.println(message);
    }
}
