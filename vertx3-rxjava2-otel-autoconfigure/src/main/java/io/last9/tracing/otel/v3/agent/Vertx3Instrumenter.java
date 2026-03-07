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
            log.info("Vertx3Instrumenter: Aerospike client instrumentation installed");
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
}
