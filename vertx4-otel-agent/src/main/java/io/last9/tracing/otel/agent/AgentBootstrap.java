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
 *   <li>Extracts the embedded library JAR ({@code inst/agent-impl.jar}) to a temp file</li>
 *   <li>Injects it onto the system classloader</li>
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
            injectLibraryOntoSystemClassLoader(inst);
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

            Path tempJar = Files.createTempFile("last9-otel-v4-agent-", ".jar");
            tempJar.toFile().deleteOnExit();
            Files.copy(embeddedStream, tempJar, StandardCopyOption.REPLACE_EXISTING);
            embeddedStream.close();

            inst.appendToSystemClassLoaderSearch(new JarFile(tempJar.toFile()));
            log("[Last9 OTel Agent v4] Library classes injected onto system classloader");
        } finally {
            agentJar.close();
        }
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
