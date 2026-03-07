package io.last9.tracing.otel.v3.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Installs ByteBuddy bytecode instrumentation for Vert.x 3 to enable zero-code
 * OpenTelemetry tracing.
 *
 * <p>Vert.x 3 has no {@code VertxTracer} SPI, so without this instrumenter, applications
 * must manually use {@code TracedRouter.create()} and {@code TracedWebClient.create()}.
 * This class intercepts the standard Vert.x factory methods at class-load time and
 * automatically wraps the results with our tracing wrappers.
 *
 * <h2>What is instrumented</h2>
 * <ul>
 *   <li>{@code Router.router(Vertx)} — installs SERVER span handlers (via
 *       {@link io.last9.tracing.otel.v3.TracedRouter})</li>
 *   <li>{@code WebClient.create(Vertx)} and {@code WebClient.create(Vertx, WebClientOptions)}
 *       — wraps with {@link io.last9.tracing.otel.v3.TracedWebClient} for CLIENT spans
 *       and {@code traceparent} injection</li>
 * </ul>
 *
 * <h2>Raw client library instrumentation</h2>
 * <p>In addition to Vert.x framework methods, this instrumenter intercepts raw client
 * libraries at the protocol level (like Datadog), covering all usage patterns without
 * requiring manual code changes:
 * <ul>
 *   <li>Kafka Producer — {@code KafkaProducer.send()}</li>
 *   <li>Kafka Consumer — {@code KafkaReadStreamImpl.handler()}</li>
 *   <li>Aerospike — {@code AerospikeClient.get/put/delete/...}</li>
 *   <li>Redis — {@code RedisConnectionImpl.send()}</li>
 *   <li>JDBC (legacy) — {@code JDBCClientImpl.query/update/...}</li>
 *   <li>MySQL reactive — {@code SqlClientBase.query/preparedQuery}</li>
 *   <li>RESTEasy (JAX-RS) — {@code SynchronousDispatcher.invoke()} for SERVER spans</li>
 * </ul>
 *
 * <p>This class is discovered by {@link io.last9.tracing.otel.OtelAgent} via reflection.
 * It is only present in the Vert.x 3 fat JAR; the Vert.x 4 fat JAR silently skips it.
 *
 * @see io.last9.tracing.otel.OtelAgent
 */
public final class Vertx3Instrumenter {

    private static final Logger log = LoggerFactory.getLogger(Vertx3Instrumenter.class);

    private Vertx3Instrumenter() {}

    /**
     * Install RxJava2 context propagation hooks and ByteBuddy class transformers.
     *
     * @param inst the JVM instrumentation handle from {@code premain}
     */
    public static void install(Instrumentation inst) {
        // RxJava2 context propagation (idempotent — safe if OtelLauncher also calls it)
        io.last9.tracing.otel.v3.RxJava2ContextPropagation.install();
        log.info("Vertx3Instrumenter: RxJava2 context propagation installed");

        installTransformersOnly(inst);
    }

    /**
     * Install only ByteBuddy class transformers, without RxJava hooks or OTel SDK init.
     *
     * <p>Used by the standalone agent ({@code vertx3-otel-agent}) which initializes
     * OTel SDK and RxJava hooks separately on the application classloader before
     * calling this method from an isolated classloader.
     *
     * @param inst the JVM instrumentation handle
     */
    public static void installTransformersOnly(Instrumentation inst) {
        AgentBuilder.Listener listener = new AgentBuilder.Listener.Adapter() {
            @Override
            public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                         JavaModule module, boolean loaded, DynamicType dynamicType) {
                log.debug("Vertx3Instrumenter: transformed {}", typeDescription.getName());
            }

            @Override
            public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                                boolean loaded, Throwable throwable) {
                log.warn("Vertx3Instrumenter: failed to transform {}: {}",
                        typeName, throwable.getMessage());
            }
        };

        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(listener)
                .disableClassFormatChanges()

                // Router.router(Vertx) → install tracing handlers on the returned Router (RxJava2 variant)
                .type(named("io.vertx.reactivex.ext.web.Router"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(RouterAdvice.class)
                                .on(isStatic()
                                        .and(named("router"))
                                        .and(takesArguments(1))
                                        .and(takesArgument(0,
                                                named("io.vertx.reactivex.core.Vertx"))))))

                // Router.router(Vertx) → install tracing handlers on the returned Router (core variant)
                .type(named("io.vertx.ext.web.Router"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(CoreRouterAdvice.class)
                                .on(isStatic()
                                        .and(named("router"))
                                        .and(takesArguments(1))
                                        .and(takesArgument(0,
                                                named("io.vertx.core.Vertx"))))))

                // WebClient.create(Vertx) / create(Vertx, WebClientOptions) → wrap result
                .type(named("io.vertx.reactivex.ext.web.client.WebClient"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(WebClientAdvice.class)
                                .on(isStatic()
                                        .and(named("create"))
                                        .and(returns(named(
                                                "io.vertx.reactivex.ext.web.client.WebClient"))))))

                .installOn(inst);

        log.info("Vertx3Instrumenter: bytecode instrumentation installed (Router, WebClient)");

        // --- Raw client library instrumentation ---
        // These are registered separately because the classes may not be on the classpath
        // (they're optional dependencies). Each is wrapped in try-catch so a missing
        // dependency doesn't prevent the other instrumentations from loading.

        installKafkaProducerInstrumentation(inst, listener);
        installKafkaConsumerInstrumentation(inst, listener);
        installAerospikeInstrumentation(inst, listener);
        installRedisInstrumentation(inst, listener);
        installJdbcInstrumentation(inst, listener);
        installReactiveSqlInstrumentation(inst, listener);
        installResteasyInstrumentation(inst, listener);
        installRawJdbcInstrumentation(inst, listener);
        installJedisInstrumentation(inst, listener);
        installLettuceInstrumentation(inst, listener);
        installNettyHttpClientInstrumentation(inst, listener);
    }

    /**
     * Kafka Producer: intercept raw {@code org.apache.kafka.clients.producer.KafkaProducer.send()}
     * to create PRODUCER spans and inject traceparent into record headers.
     */
    private static void installKafkaProducerInstrumentation(Instrumentation inst,
                                                             AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("org.apache.kafka.clients.producer.KafkaProducer"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(KafkaProducerAdvice.class)
                                    .on(named("send")
                                            .and(takesArguments(2))
                                            .and(takesArgument(0, named(
                                                    "org.apache.kafka.clients.producer.ProducerRecord")))
                                            .and(takesArgument(1, named(
                                                    "org.apache.kafka.clients.producer.Callback"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Kafka producer instrumentation installed");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: Kafka producer instrumentation skipped — "
                    + "kafka-clients not on classpath: {}", t.getMessage());
        }
    }

    /**
     * Kafka Consumer: intercept per-record handler registration on
     * {@code io.vertx.kafka.client.consumer.impl.KafkaReadStreamImpl} to wrap
     * with CONSUMER spans per record.
     */
    private static void installKafkaConsumerInstrumentation(Instrumentation inst,
                                                              AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.kafka.client.consumer.impl.KafkaReadStreamImpl"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(KafkaConsumerAdvice.class)
                                    .on(named("handler")
                                            .and(takesArguments(1)))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Kafka consumer instrumentation installed");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: Kafka consumer instrumentation skipped — "
                    + "vertx-kafka-client not on classpath: {}", t.getMessage());
        }
    }

    /**
     * Aerospike: intercept single-key data methods on
     * {@code com.aerospike.client.AerospikeClient} to create CLIENT spans.
     */
    private static void installAerospikeInstrumentation(Instrumentation inst,
                                                         AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("com.aerospike.client.AerospikeClient"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(AerospikeClientAdvice.class)
                                    .on(namedOneOf("get", "put", "delete", "exists",
                                            "operate", "touch", "append", "prepend",
                                            "add", "getHeader", "execute")
                                            .and(not(takesArguments(0)))
                                            .and(takesArgument(1, named(
                                                    "com.aerospike.client.Key"))))))
                    .installOn(inst);
            // Batch operations: get(BatchPolicy, Key[]), exists(BatchPolicy, Key[]),
            // getHeader(BatchPolicy, Key[])
            // Dream11's bulkGetBins() delegates to these — ~20K rpm in production.
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("com.aerospike.client.AerospikeClient"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(AerospikeBatchAdvice.class)
                                    .on(namedOneOf("get", "exists", "getHeader")
                                            .and(not(takesArguments(0)))
                                            .and(takesArgument(1, isArray())))))
                    .installOn(inst);

            log.info("Vertx3Instrumenter: Aerospike client instrumentation installed (single-key + batch)");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: Aerospike instrumentation skipped — "
                    + "aerospike-client not on classpath: {}", t.getMessage());
        }
    }

    /**
     * Redis: intercept {@code io.vertx.redis.client.impl.RedisConnectionImpl.send(Request, Handler)}
     * to create CLIENT spans for all Redis commands.
     */
    private static void installRedisInstrumentation(Instrumentation inst,
                                                     AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.redis.client.impl.RedisConnectionImpl"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(RedisConnectionAdvice.class)
                                    .on(named("send")
                                            .and(takesArguments(2))
                                            .and(takesArgument(0, named(
                                                    "io.vertx.redis.client.Request"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Redis connection instrumentation installed");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: Redis instrumentation skipped — "
                    + "vertx-redis-client not on classpath: {}", t.getMessage());
        }
    }

    /**
     * JDBC (legacy SQLClient): intercept SQL methods on
     * {@code io.vertx.ext.jdbc.impl.JDBCClientImpl} to create CLIENT spans.
     */
    private static void installJdbcInstrumentation(Instrumentation inst,
                                                    AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.ext.jdbc.impl.JDBCClientImpl"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(JdbcClientAdvice.class)
                                    .on(namedOneOf("query", "queryWithParams",
                                            "queryStream", "queryStreamWithParams",
                                            "querySingle", "querySingleWithParams",
                                            "update", "updateWithParams",
                                            "call", "callWithParams")
                                            .and(takesArgument(0, String.class)))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: JDBC client instrumentation installed");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: JDBC instrumentation skipped — "
                    + "vertx-jdbc-client not on classpath: {}", t.getMessage());
        }
    }

    /**
     * RESTEasy (JAX-RS): intercept {@code SynchronousDispatcher.invoke(HttpRequest, HttpResponse)}
     * to create SERVER spans for JAX-RS endpoints running on Vert.x.
     *
     * <p>This covers applications that use RESTEasy for business routes instead of
     * the Vert.x Router (e.g., fantasy-tour-v1). The dispatch is synchronous, so
     * the OTel context is current during the entire resource method execution.
     */
    private static void installResteasyInstrumentation(Instrumentation inst,
                                                        AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("org.jboss.resteasy.core.SynchronousDispatcher"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(ResteasyDispatchAdvice.class)
                                    .on(named("invoke")
                                            .and(takesArguments(2))
                                            .and(takesArgument(0, named(
                                                    "org.jboss.resteasy.spi.HttpRequest")))
                                            .and(takesArgument(1, named(
                                                    "org.jboss.resteasy.spi.HttpResponse"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: RESTEasy dispatcher instrumentation installed");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: RESTEasy instrumentation skipped — "
                    + "resteasy not on classpath: {}", t.getMessage());
        }
    }

    /**
     * MySQL reactive (SqlClientBase): intercept {@code query(String)} and
     * {@code preparedQuery(String)} on the reactive SQL client implementation.
     */
    private static void installReactiveSqlInstrumentation(Instrumentation inst,
                                                           AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.sqlclient.impl.SqlClientBase"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(ReactiveSqlAdvice.class)
                                    .on(namedOneOf("query", "preparedQuery")
                                            .and(takesArguments(1))
                                            .and(takesArgument(0, String.class)))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Reactive SQL client instrumentation installed");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: Reactive SQL instrumentation skipped — "
                    + "vertx-sql-client not on classpath: {}", t.getMessage());
        }
    }

    /**
     * Raw JDBC: intercept {@code java.sql.Statement} implementations to create
     * CLIENT spans for any JDBC usage (MySQL, PostgreSQL, H2, Oracle, etc.).
     *
     * <p>This complements the Vert.x JDBCClientImpl instrumentation by also
     * covering direct Statement usage without the Vert.x wrapper. The AgentGuard
     * prevents double-instrumentation when both are active.
     */
    private static void installRawJdbcInstrumentation(Instrumentation inst,
                                                       AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(isSubTypeOf(java.sql.Statement.class).and(not(isInterface())))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(JdbcStatementAdvice.class)
                                    .on(namedOneOf("execute", "executeQuery", "executeUpdate")
                                            .and(takesArgument(0, String.class)))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Raw JDBC Statement instrumentation installed");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: Raw JDBC instrumentation skipped: {}", t.getMessage());
        }
    }

    /**
     * Jedis: intercept {@code redis.clients.jedis.Connection.sendCommand()} to create
     * CLIENT spans for all Jedis Redis operations.
     *
     * <p>Covers Jedis, JedisPool, JedisCluster, and Pipeline usage.
     */
    private static void installJedisInstrumentation(Instrumentation inst,
                                                     AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("redis.clients.jedis.Connection"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(JedisAdvice.class)
                                    .on(named("sendCommand")
                                            .and(takesArguments(2)))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Jedis Redis instrumentation installed");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: Jedis instrumentation skipped — "
                    + "jedis not on classpath: {}", t.getMessage());
        }
    }

    /**
     * Lettuce: intercept {@code io.lettuce.core.AbstractRedisAsyncCommands.dispatch()}
     * to create CLIENT spans for all Lettuce Redis operations.
     *
     * <p>Covers sync, async, reactive, and pipelining usage patterns.
     */
    private static void installLettuceInstrumentation(Instrumentation inst,
                                                       AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.lettuce.core.AbstractRedisAsyncCommands"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(LettuceAdvice.class)
                                    .on(named("dispatch")
                                            .and(takesArguments(1)))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Lettuce Redis instrumentation installed");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: Lettuce instrumentation skipped — "
                    + "lettuce-core not on classpath: {}", t.getMessage());
        }
    }

    /**
     * Netty HTTP Client: intercept outgoing HTTP requests through Vert.x's core
     * HTTP client at the {@code HttpClientRequestImpl} level.
     *
     * <p>Three intercept points for full round-trip visibility:
     * <ol>
     *   <li>{@code HttpClientRequestImpl.end()} — span creation + traceparent injection</li>
     *   <li>{@code HttpClientRequestBase.handleResponse()} — response status code + span end</li>
     *   <li>{@code HttpClientRequestBase.handleException()} — error recording + span end</li>
     * </ol>
     *
     * <p>The WebClient advice already covers WebClient.create() wrapping, so the
     * helper uses a ThreadLocal guard to prevent double-instrumentation.
     */
    private static void installNettyHttpClientInstrumentation(Instrumentation inst,
                                                                AgentBuilder.Listener listener) {
        try {
            // 1. Intercept end() on HttpClientRequestImpl — creates span + injects traceparent
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.core.http.impl.HttpClientRequestImpl"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(NettyHttpClientAdvice.class)
                                    .on(named("end")
                                            .and(takesArguments(0)))))
                    .installOn(inst);

            // 2. Intercept handleResponse() on HttpClientRequestBase — sets status code, ends span
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.core.http.impl.HttpClientRequestBase"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(NettyHttpResponseAdvice.class)
                                    .on(named("handleResponse")
                                            .and(takesArguments(1)))))
                    .installOn(inst);

            // 3. Intercept handleException() on HttpClientRequestBase — records error, ends span
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.core.http.impl.HttpClientRequestBase"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(NettyHttpExceptionAdvice.class)
                                    .on(named("handleException")
                                            .and(takesArguments(1)))))
                    .installOn(inst);

            log.info("Vertx3Instrumenter: Netty HTTP client instrumentation installed");
        } catch (Throwable t) {
            log.debug("Vertx3Instrumenter: Netty HTTP client instrumentation skipped: {}",
                    t.getMessage());
        }
    }
}
