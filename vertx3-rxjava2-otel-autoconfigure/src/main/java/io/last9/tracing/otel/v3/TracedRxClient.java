package io.last9.tracing.otel.v3;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;

/**
 * Generic dynamic proxy that wraps any RxJava2-based client interface with
 * OpenTelemetry CLIENT spans.
 *
 * <p>Methods returning {@link Single}, {@link Completable}, or {@link Maybe} are
 * automatically wrapped with a traced span using {@link DbTracing}. All other methods
 * pass through unmodified.
 *
 * <p>This enables tracing for third-party clients that the library has no compile-time
 * dependency on — e.g., third-party MySQL/Aerospike clients, or any custom RxJava2
 * data-access layer.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Wrap a MySQL client:
 * MysqlClient traced = TracedRxClient.wrap(
 *         mysqlClient, MysqlClient.class, "mysql", "orders_db");
 *
 * // Wrap an Aerospike client:
 * AerospikeClient traced = TracedRxClient.wrap(
 *         aerospikeClient, AerospikeClient.class, "aerospike", "my-namespace");
 *
 * // All RxJava2 method calls now produce CLIENT spans automatically:
 * traced.rxQuery("SELECT * FROM users")
 *     .subscribe(result -> ...);
 * }</pre>
 *
 * <h2>Span naming</h2>
 * <p>By default, the span operation name is the method name (e.g., {@code rxQuery},
 * {@code rxGet}). You can provide a custom {@code operationNameFn} to extract a more
 * descriptive name from the method and arguments:
 * <pre>{@code
 * TracedRxClient.wrap(client, MyClient.class, "mysql", "mydb",
 *         (method, args) -> method.getName() + " " + args[0]);  // e.g., "rxQuery SELECT ..."
 * }</pre>
 *
 * @see DbTracing
 * @see TracedAerospikeClient
 */
public final class TracedRxClient {

    private TracedRxClient() {
        // Utility class
    }

    /**
     * Functional interface for extracting a span operation name from a method invocation.
     */
    @FunctionalInterface
    public interface OperationNameFn {
        /**
         * Returns the operation name for the given method and arguments.
         *
         * @param method the invoked method
         * @param args   the method arguments (may be null)
         * @return the operation name for the span
         */
        String apply(Method method, Object[] args);
    }

    /**
     * Wraps a client with automatic RxJava2 tracing using {@link GlobalOpenTelemetry}.
     * The {@code db.name} attribute is omitted from spans.
     *
     * @param <T>      the client interface type
     * @param client   the client instance to wrap
     * @param iface    the interface class to proxy
     * @param dbSystem the database system identifier (e.g., "mysql", "aerospike")
     * @return a traced proxy implementing the given interface
     */
    public static <T> T wrap(T client, Class<T> iface, String dbSystem) {
        return wrap(client, iface, dbSystem, null, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps a client with automatic RxJava2 tracing using {@link GlobalOpenTelemetry}.
     *
     * @param <T>          the client interface type
     * @param client       the client instance to wrap
     * @param iface        the interface class to proxy
     * @param dbSystem     the database system identifier (e.g., "mysql", "aerospike")
     * @param dbNamespace  the database or namespace name; may be {@code null} to omit
     * @return a traced proxy implementing the given interface
     */
    public static <T> T wrap(T client, Class<T> iface, String dbSystem, String dbNamespace) {
        return wrap(client, iface, dbSystem, dbNamespace, GlobalOpenTelemetry.get());
    }

    /**
     * Wraps a client with automatic RxJava2 tracing using the supplied {@link OpenTelemetry}.
     *
     * @param <T>            the client interface type
     * @param client         the client instance to wrap
     * @param iface          the interface class to proxy
     * @param dbSystem       the database system identifier
     * @param dbNamespace    the database or namespace name
     * @param openTelemetry  the OpenTelemetry instance to use
     * @return a traced proxy implementing the given interface
     */
    public static <T> T wrap(T client, Class<T> iface, String dbSystem, String dbNamespace,
                              OpenTelemetry openTelemetry) {
        return wrap(client, iface, dbSystem, dbNamespace, openTelemetry, null);
    }

    /**
     * Wraps a client with automatic RxJava2 tracing and a custom operation name function.
     *
     * @param <T>              the client interface type
     * @param client           the client instance to wrap
     * @param iface            the interface class to proxy
     * @param dbSystem         the database system identifier
     * @param dbNamespace      the database or namespace name
     * @param operationNameFn  custom function to derive span operation name (nullable,
     *                         defaults to method name)
     * @return a traced proxy implementing the given interface
     */
    public static <T> T wrap(T client, Class<T> iface, String dbSystem, String dbNamespace,
                              OperationNameFn operationNameFn) {
        return wrap(client, iface, dbSystem, dbNamespace, GlobalOpenTelemetry.get(), operationNameFn);
    }

    /**
     * Wraps a client with automatic RxJava2 tracing, custom OpenTelemetry, and custom
     * operation name function.
     *
     * @param <T>              the client interface type
     * @param client           the client instance to wrap
     * @param iface            the interface class to proxy
     * @param dbSystem         the database system identifier
     * @param dbNamespace      the database or namespace name
     * @param openTelemetry    the OpenTelemetry instance to use
     * @param operationNameFn  custom function to derive span operation name (nullable)
     * @return a traced proxy implementing the given interface
     */
    @SuppressWarnings("unchecked")
    public static <T> T wrap(T client, Class<T> iface, String dbSystem, String dbNamespace,
                              OpenTelemetry openTelemetry, OperationNameFn operationNameFn) {
        DbTracing db = DbTracing.create(dbSystem, dbNamespace, openTelemetry);
        OperationNameFn nameFn = operationNameFn != null
                ? operationNameFn
                : (method, args) -> method.getName();

        InvocationHandler handler = new RxTracingHandler<>(client, db, nameFn);
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                handler);
    }

    private static class RxTracingHandler<T> implements InvocationHandler {

        private final T delegate;
        private final DbTracing db;
        private final OperationNameFn operationNameFn;

        RxTracingHandler(T delegate, DbTracing db, OperationNameFn operationNameFn) {
            this.delegate = delegate;
            this.db = db;
            this.operationNameFn = operationNameFn;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Pass through Object methods (toString, equals, hashCode)
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }

            Class<?> returnType = method.getReturnType();
            String operation = operationNameFn.apply(method, args);

            if (Single.class.isAssignableFrom(returnType)) {
                return db.traceSingle(operation, () -> {
                    try {
                        return (Single<?>) invokeDelegate(method, args);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
            }

            if (Completable.class.isAssignableFrom(returnType)) {
                return db.traceCompletable(operation, () -> {
                    try {
                        return (Completable) invokeDelegate(method, args);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
            }

            if (Maybe.class.isAssignableFrom(returnType)) {
                return db.traceMaybe(operation, () -> {
                    try {
                        return (Maybe<?>) invokeDelegate(method, args);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
            }

            // Non-Rx methods pass through without tracing
            return invokeDelegate(method, args);
        }

        private Object invokeDelegate(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
