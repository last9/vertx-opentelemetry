package io.last9.tracing.otel.agent;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Isolated classloader for the agent's ByteBuddy instrumentation.
 *
 * <p>Class resolution order:
 * <ol>
 *   <li><b>Already loaded</b> — returns cached class</li>
 *   <li><b>Bootstrap classloader</b> — JDK classes ({@code java.*}, {@code javax.*}, etc.)</li>
 *   <li><b>Embedded library JAR</b> — ByteBuddy, OTel SDK, advice classes, Vertx3Instrumenter</li>
 *   <li><b>Fallback (system classloader)</b> — SLF4J, Vert.x, RxJava, and other app-provided types</li>
 * </ol>
 *
 * <p>The fallback to the system classloader is intentional: ByteBuddy advice classes reference
 * types like {@code io.vertx.core.Handler} which are only on the app classpath. These are
 * resolved via fallback when the advice is loaded for bytecode reading. At runtime, the inlined
 * advice resolves types from the target class's classloader (the app classloader).
 *
 * <p>Crucially, our library classes (TracedRouter, OTel SDK, ByteBuddy) are found in the
 * embedded JAR <b>before</b> the fallback, so they never leak onto the system classloader.
 */
public class AgentClassLoader extends URLClassLoader {

    private final ClassLoader fallback;

    /**
     * @param urls     URLs pointing to the embedded library JAR (extracted to temp file)
     * @param fallback classloader for app-provided types (SLF4J, Vert.x, RxJava)
     */
    public AgentClassLoader(URL[] urls, ClassLoader fallback) {
        super(urls, null); // parent = bootstrap classloader
        this.fallback = fallback;
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1. Already loaded?
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;

            // 2. Bootstrap classloader (JDK classes)
            try {
                c = Class.forName(name, false, null);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException ignored) {
                // Not a JDK class — continue
            }

            // 3. Embedded library JAR (ByteBuddy, our instrumentation, OTel SDK)
            try {
                c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException ignored) {
                // Not in embedded JAR — fall back
            }

            // 4. Fallback to system classloader (SLF4J, Vert.x, RxJava — app-provided)
            if (fallback != null) {
                c = fallback.loadClass(name);
                if (resolve) resolveClass(c);
                return c;
            }

            throw new ClassNotFoundException(name);
        }
    }
}
