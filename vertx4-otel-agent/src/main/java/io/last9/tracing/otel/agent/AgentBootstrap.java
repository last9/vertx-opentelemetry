package io.last9.tracing.otel.agent;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Java agent entry point for Vert.x 4 — zero-dependency, works out of the box.
 *
 * <p>This is the <b>only</b> class that the JVM places on the system classloader
 * when used as {@code -javaagent}. All heavy dependencies (ByteBuddy, OTel SDK,
 * instrumentation helpers) are embedded inside the agent JAR and injected onto
 * the system classloader at startup.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * java -javaagent:vertx4-otel-agent.jar -jar app.jar
 * }</pre>
 *
 * <h2>What happens at startup</h2>
 * <ol>
 *   <li>Injects {@code opentelemetry-context.jar} and {@code opentelemetry-api.jar} (1.38.0)
 *       onto the <b>bootstrap classloader</b> — this shadows any older OTel API the app carries
 *       (e.g. 1.18.0 from {@code vertx-opentelemetry:4.5.x}) and ensures {@code getLogsBridge()}
 *       and the full 1.38.0 interface surface are always available</li>
 *   <li>Extracts the embedded library JAR ({@code inst/agent-impl.jar}) to a temp file
 *       and injects it onto the system classloader</li>
 *   <li>Stores the {@code Instrumentation} handle via {@code OtelAgent.storeInstrumentation()}</li>
 *   <li>Installs ByteBuddy class transformers — <b>before</b> OTel SDK init</li>
 *   <li>Initializes the OTel SDK</li>
 *   <li>Installs RxJava3 context propagation hooks</li>
 * </ol>
 *
 * <p>The Vert.x 4 agent intercepts {@code Vertx.vertx(VertxOptions)} to inject
 * {@code TracingOptions} (enabling the VertxTracer SPI) and {@code MicrometerMetricsOptions}
 * (enabling Vert.x internal metrics). For third-party libraries not covered by
 * the SPI (Jedis, Lettuce, JDBC, Aerospike, RESTEasy), ByteBuddy advices create
 * CLIENT spans directly.
 */
public final class AgentBootstrap {

    private static final String EMBEDDED_JAR = "inst/agent-impl.jar";
    // Context must be listed before API — API classes import io.opentelemetry.context.*
    // so Context must be on the bootstrap classpath first.
    private static final String[] BOOTSTRAP_JARS = {
        "inst/bootstrap-opentelemetry-context.jar",
        "inst/bootstrap-opentelemetry-api.jar"
    };
    private static final String INSTRUMENTER_CLASS =
            "io.last9.tracing.otel.v4.agent.Vertx4Instrumenter";
    private static final String SDK_SETUP_CLASS =
            "io.last9.tracing.otel.OtelSdkSetup";
    private static final String RX_PROPAGATION_CLASS =
            "io.last9.tracing.otel.v4.RxJava3ContextPropagation";
    private static final String OTEL_AGENT_CLASS =
            "io.last9.tracing.otel.OtelAgent";

    private AgentBootstrap() {}

    /**
     * Called by the JVM before the application's {@code main} method.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            File agentJarFile = new File(AgentBootstrap.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            JarFile agentJar = new JarFile(agentJarFile);
            try {
                // Must be first — shadows the app's OTel API before any class loads.
                bootstrapInjectOtelApi(inst, agentJar);
                injectLibraryOntoSystemClassLoader(inst, agentJar);
            } finally {
                agentJar.close();
            }
            storeInstrumentationOnAppClassLoader(inst);
            installTransformers(inst);
            initializeOnAppClassLoader();
            log("[Last9 OTel Agent v4] Zero-code instrumentation installed successfully");
        } catch (Exception e) {
            log("[Last9 OTel Agent v4] Failed to install instrumentation: " + e.getMessage());
            e.printStackTrace(System.err);
            log("[Last9 OTel Agent v4] Application will start WITHOUT bytecode instrumentation.");
        }
    }

    /**
     * Called when this agent is attached dynamically.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    /**
     * Injects OpenTelemetry API and Context JARs onto the bootstrap classloader.
     *
     * <p>Bootstrap is the top of the classloader hierarchy — all classloaders delegate to it
     * first (parent delegation). Placing our OTel API 1.38.0 here means every classloader
     * in the JVM sees it, regardless of what older version the app has on its own classpath.
     */
    private static void bootstrapInjectOtelApi(Instrumentation inst, JarFile agentJar)
            throws Exception {
        for (String resource : BOOTSTRAP_JARS) {
            Path tempJar = extractEmbeddedJar(agentJar, resource);
            if (tempJar == null) {
                log("[Last9 OTel Agent v4] WARNING: Bootstrap JAR not found in agent: " + resource);
                continue;
            }
            inst.appendToBootstrapClassLoaderSearch(new JarFile(tempJar.toFile()));
        }
        log("[Last9 OTel Agent v4] OTel API 1.38.0 injected onto bootstrap classloader"
                + " (shadows any older version on app classpath)");
    }

    private static void injectLibraryOntoSystemClassLoader(Instrumentation inst, JarFile agentJar)
            throws Exception {
        Path tempJar = extractEmbeddedJar(agentJar, EMBEDDED_JAR);
        if (tempJar == null) {
            throw new IllegalStateException("Embedded JAR not found in agent: " + EMBEDDED_JAR);
        }
        inst.appendToSystemClassLoaderSearch(new JarFile(tempJar.toFile()));
        log("[Last9 OTel Agent v4] Library classes injected onto system classloader");
    }

    /**
     * Extracts a named entry from the agent JAR to a temp file and returns its path.
     * Returns {@code null} if the entry does not exist in the JAR.
     */
    private static Path extractEmbeddedJar(JarFile agentJar, String resource) throws Exception {
        JarEntry entry = agentJar.getJarEntry(resource);
        if (entry == null) return null;
        Path tempJar = Files.createTempFile("last9-agent-", ".jar");
        // deleteOnExit is best-effort (no-op on SIGKILL). The JVM keeps the bytes
        // accessible via the open JarFile handle regardless of file-system deletion.
        tempJar.toFile().deleteOnExit();
        try (InputStream stream = agentJar.getInputStream(entry)) {
            Files.copy(stream, tempJar, StandardCopyOption.REPLACE_EXISTING);
        }
        return tempJar;
    }

    private static void storeInstrumentationOnAppClassLoader(Instrumentation inst)
            throws Exception {
        ClassLoader appCL = ClassLoader.getSystemClassLoader();
        Class<?> otelAgent = appCL.loadClass(OTEL_AGENT_CLASS);
        otelAgent.getMethod("storeInstrumentation", Instrumentation.class).invoke(null, inst);
    }

    private static void initializeOnAppClassLoader() throws Exception {
        ClassLoader appCL = ClassLoader.getSystemClassLoader();

        // Initialize OTel SDK
        Class<?> sdkSetup = appCL.loadClass(SDK_SETUP_CLASS);
        sdkSetup.getMethod("initialize").invoke(null);
        log("[Last9 OTel Agent v4] OpenTelemetry SDK initialized");

        // Install RxJava3 context propagation hooks
        try {
            Class<?> rxPropagation = appCL.loadClass(RX_PROPAGATION_CLASS);
            rxPropagation.getMethod("install").invoke(null);
            log("[Last9 OTel Agent v4] RxJava3 context propagation installed");
        } catch (ClassNotFoundException e) {
            // RxJava3 not on classpath — skip
        }
    }

    private static void installTransformers(Instrumentation inst)
            throws Exception {
        ClassLoader appCL = ClassLoader.getSystemClassLoader();
        Class<?> instrumenter = appCL.loadClass(INSTRUMENTER_CLASS);
        instrumenter.getMethod("installTransformersOnly", Instrumentation.class)
                .invoke(null, inst);
        log("[Last9 OTel Agent v4] ByteBuddy class transformers installed");
    }

    private static void log(String message) {
        System.err.println(message);
    }
}
