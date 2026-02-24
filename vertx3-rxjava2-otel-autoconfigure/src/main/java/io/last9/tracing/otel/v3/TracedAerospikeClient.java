package io.last9.tracing.otel.v3;

import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Creates a dynamic proxy around {@link IAerospikeClient} that automatically wraps
 * synchronous data-plane operations with OpenTelemetry CLIENT spans.
 *
 * <p>Vert.x 3 has no tracing SPI for Aerospike. This utility uses Java's
 * {@link Proxy} mechanism to intercept all method calls on the interface without
 * requiring manual implementation of the 60+ methods in {@code IAerospikeClient}.
 *
 * <p>Only synchronous data-plane methods ({@code get}, {@code put}, {@code delete},
 * {@code exists}, {@code operate}, {@code query}, {@code scanAll}, etc.) are traced.
 * Async variants (those taking an {@code EventLoop} parameter) and admin/lifecycle
 * methods pass through without tracing.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * IAerospikeClient client = new AerospikeClient("localhost", 3000);
 * IAerospikeClient traced = TracedAerospikeClient.wrap(client, "my-namespace");
 *
 * // Every data-plane call automatically gets a CLIENT span:
 * Record record = traced.get(null, new Key("my-namespace", "users", "user:123"));
 * traced.put(null, key, new Bin("name", "Alice"));
 * }</pre>
 *
 * @see DbTracing
 * @see IAerospikeClient
 */
public final class TracedAerospikeClient {

    private static final Set<String> TRACED_METHODS = new HashSet<>(Arrays.asList(
            "get", "put", "delete", "exists", "operate",
            "query", "scanAll", "scanPartitions", "scanNode",
            "append", "prepend", "add", "touch",
            "execute", "truncate", "getHeader"
    ));

    private TracedAerospikeClient() {
    }

    /**
     * Wraps an {@link IAerospikeClient} with tracing using {@link GlobalOpenTelemetry}.
     * The {@code db.name} attribute is omitted from spans.
     *
     * @param client the Aerospike client to wrap
     * @return a traced proxy implementing {@code IAerospikeClient}
     */
    public static IAerospikeClient wrap(IAerospikeClient client) {
        return wrap(client, null, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an {@link IAerospikeClient} with tracing using {@link GlobalOpenTelemetry}.
     *
     * @param client      the Aerospike client to wrap
     * @param dbNamespace the Aerospike namespace (used as {@code db.name}); may be {@code null}
     * @return a traced proxy implementing {@code IAerospikeClient}
     */
    public static IAerospikeClient wrap(IAerospikeClient client, String dbNamespace) {
        return wrap(client, dbNamespace, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps an {@link IAerospikeClient} with tracing using the supplied {@link OpenTelemetry}.
     * The {@code db.name} attribute is omitted from spans. Useful in tests.
     *
     * @param client        the Aerospike client to wrap
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a traced proxy implementing {@code IAerospikeClient}
     */
    public static IAerospikeClient wrap(IAerospikeClient client, OpenTelemetry openTelemetry) {
        return wrap(client, null, openTelemetry);
    }

    /**
     * Wraps an {@link IAerospikeClient} with tracing using the supplied {@link OpenTelemetry}.
     * Useful in tests.
     *
     * @param client        the Aerospike client to wrap
     * @param dbNamespace   the Aerospike namespace; may be {@code null} to omit
     * @param openTelemetry the OpenTelemetry instance to use
     * @return a traced proxy implementing {@code IAerospikeClient}
     */
    public static IAerospikeClient wrap(IAerospikeClient client, String dbNamespace,
                                        OpenTelemetry openTelemetry) {
        DbTracing db = DbTracing.create("aerospike", dbNamespace, openTelemetry);
        InvocationHandler handler = new TracingHandler(client, db);
        return (IAerospikeClient) Proxy.newProxyInstance(
                IAerospikeClient.class.getClassLoader(),
                new Class<?>[]{IAerospikeClient.class},
                handler);
    }

    private static class TracingHandler implements InvocationHandler {

        private final IAerospikeClient delegate;
        private final DbTracing db;

        TracingHandler(IAerospikeClient delegate, DbTracing db) {
            this.delegate = delegate;
            this.db = db;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!shouldTrace(method, args)) {
                return invokeDelegate(method, args);
            }

            String operation = formatOperation(method.getName(), args);
            return db.traceSync(operation, () -> {
                try {
                    return invokeDelegate(method, args);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            });
        }

        private Object invokeDelegate(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private boolean shouldTrace(Method method, Object[] args) {
            if (!TRACED_METHODS.contains(method.getName())) {
                return false;
            }
            // Skip async variants — they have an EventLoop as the first parameter
            if (args != null && args.length > 0) {
                Class<?> firstParamType = method.getParameterTypes()[0];
                if (firstParamType.getName().contains("EventLoop")) {
                    return false;
                }
            }
            return true;
        }

        private String formatOperation(String methodName, Object[] args) {
            String command = methodName.toUpperCase();
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof Key) {
                        Key key = (Key) arg;
                        return command + " " + key.namespace + "." + key.setName;
                    }
                    if (arg instanceof Key[]) {
                        Key[] keys = (Key[]) arg;
                        return command + " (" + keys.length + " keys)";
                    }
                }
            }
            return command;
        }
    }
}
