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
 *   <li>All outgoing HTTP via {@code HttpClientRequestImpl.end()} (Netty level) — creates
 *       CLIENT spans and injects {@code traceparent}. This covers WebClient, HttpClient,
 *       and any other Vert.x HTTP client usage without changing the WebClient class identity.</li>
 * </ul>
 *
 * <h2>Raw client library instrumentation</h2>
 * <p>In addition to Vert.x framework methods, this instrumenter intercepts raw client
 * libraries at the protocol level, covering all usage patterns without
 * requiring manual code changes:
 * <ul>
 *   <li>Kafka Producer — {@code KafkaProducer.send()}</li>
 *   <li>Kafka Consumer — {@code KafkaReadStreamImpl.handler()}</li>
 *   <li>Aerospike — {@code AerospikeClient.get/put/delete/...}</li>
 *   <li>Redis — {@code RedisConnectionImpl.send()}</li>
 *   <li>JDBC (legacy) — {@code JDBCClientImpl.query/update/...}</li>
 *   <li>MySQL reactive — {@code SqlClientBase.query/preparedQuery}</li>
 *   <li>RESTEasy (JAX-RS) — {@code SynchronousDispatcher.invoke()} for SERVER spans</li>
 *   <li>AWS SQS — {@code AmazonSQSClient/DefaultSqsClient.receiveMessage()} for CONSUMER spans</li>
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
                log.info("Vertx3Instrumenter: transformed {} (loaded={})",
                        typeDescription.getName(), loaded);
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

                // NOTE: WebClient.create() is intentionally NOT intercepted here.
                // Wrapping WebClient.create() with TracedWebClient changes the concrete
                // class of the returned object. Code that uses object.getClass().getName()
                // as a key (e.g. ContextUtils.setInstance) stores under "TracedWebClient"
                // but retrieves under "WebClient", producing null. CLIENT spans and
                // traceparent injection are handled by NettyHttpClientHelper at the
                // HttpClientRequestImpl.end() level, which covers ALL outgoing HTTP
                // regardless of how the WebClient was created.
                .installOn(inst);

        log.info("Vertx3Instrumenter: bytecode instrumentation installed (Router, WebClient)");

        // Netty pipeline: inject tracing handler when HttpServerCodec is added.
        // Creates SERVER spans at the lowest possible level (Netty), matching the approach
        // used by Datadog and OTel Java agents. Works even if requestHandler() is never called.
        installNettyServerPipelineInstrumentation(inst, listener);

        // HttpServer.requestHandler() — creates SERVER spans for ALL incoming HTTP requests,
        // regardless of whether a Router is used. If the Netty handler already created a span,
        // HttpServerAdviceHelper adopts it (manages scope only, no duplicate).
        installHttpServerInstrumentation(inst, listener);

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
        installSqsInstrumentation(inst, listener);
    }

    /**
     * HttpServer: intercept {@code HttpServerImpl.requestHandler(Handler)} to wrap the handler
     * with SERVER span creation. This creates SERVER spans for ALL incoming HTTP requests,
     * regardless of whether a Vert.x Router is used.
     */
    private static void installHttpServerInstrumentation(Instrumentation inst,
                                                          AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.core.http.impl.HttpServerImpl"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(HttpServerAdvice.class)
                                    .on(named("requestHandler")
                                            .and(takesArguments(1))
                                            .and(takesArgument(0, named("io.vertx.core.Handler"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: HTTP server instrumentation installed (SERVER spans for all requests)");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: HTTP server instrumentation skipped: {}", t.getMessage());
        }

        // HttpStreamHandler.handler() — covers the requestStream().toFlowable() pattern.
        // In Vert.x 3.9.x, requestStream().toFlowable() sets the handler directly on the
        // inner HttpStreamHandler class via putfield, bypassing requestHandler(). This
        // intercept catches that code path so apps using requestStream get SERVER spans.
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.core.http.impl.HttpServerImpl$HttpStreamHandler"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(HttpServerAdvice.class)
                                    .on(named("handler")
                                            .and(takesArguments(1))
                                            .and(takesArgument(0, named("io.vertx.core.Handler"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: HTTP server requestStream instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: HTTP server requestStream instrumentation skipped: {}", t.getMessage());
        }
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
            log.warn("Vertx3Instrumenter: Kafka producer instrumentation skipped — "
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
            log.warn("Vertx3Instrumenter: Kafka consumer instrumentation skipped — "
                    + "vertx-kafka-client not on classpath: {}", t.getMessage());
        }
    }

    /**
     * Aerospike: pattern-based instrumentation matching all public methods with a
     * Policy-subclass first argument (sync) or listener second argument (async).
     *
     * <p>Same approach as Datadog: match by argument type pattern instead of listing
     * specific method names. Automatically covers new API methods added in future
     * Aerospike client versions.
     */
    private static void installAerospikeInstrumentation(Instrumentation inst,
                                                         AgentBuilder.Listener listener) {
        // Sync: all public methods with Policy subclass as arg0
        // Covers: get, put, delete, exists, operate, touch, scanAll, query, execute, etc.
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("com.aerospike.client.AerospikeClient"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(AerospikeSyncAdvice.class)
                                    .on(isPublic()
                                            .and(isMethod())
                                            .and(not(takesArguments(0)))
                                            .and(takesArgument(0, nameStartsWith(
                                                    "com.aerospike.client.policy"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Aerospike sync instrumentation installed (pattern-based)");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: Aerospike sync instrumentation skipped — "
                    + "aerospike-client not on classpath: {}", t.getMessage());
        }

        // Async: all public methods with a listener as arg1
        // Covers: async get, put, delete, exists, operate, etc.
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("com.aerospike.client.AerospikeClient"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(AerospikeAsyncAdvice.class)
                                    .on(isPublic()
                                            .and(isMethod())
                                            .and(takesArgument(1, nameStartsWith(
                                                    "com.aerospike.client.listener"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Aerospike async instrumentation installed (listener wrapping)");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: Aerospike async instrumentation skipped: {}", t.getMessage());
        }

        // SyncCommand.execute() — catches ALL operations regardless of client class
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("com.aerospike.client.command.SyncCommand"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(AerospikeSyncCommandAdvice.class)
                                    .on(named("execute").and(takesArguments(0)))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Aerospike SyncCommand instrumentation installed (command-level)");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: Aerospike SyncCommand instrumentation skipped: {}", t.getMessage());
        }

        // Command.getNode() — enriches span with connection metadata (host, port, namespace)
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("com.aerospike.client.command.Command"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(AerospikeCommandNodeAdvice.class)
                                    .on(named("getNode")
                                            .and(takesArguments(2))
                                            .and(returns(named(
                                                    "com.aerospike.client.cluster.Node"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Aerospike connection metadata instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: Aerospike connection metadata skipped: {}", t.getMessage());
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
            log.warn("Vertx3Instrumenter: Redis instrumentation skipped — "
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
            log.warn("Vertx3Instrumenter: JDBC instrumentation skipped — "
                    + "vertx-jdbc-client not on classpath: {}", t.getMessage());
        }
    }

    /**
     * RESTEasy (JAX-RS): intercept {@code SynchronousDispatcher.invoke(HttpRequest, HttpResponse)}
     * to create SERVER spans for JAX-RS endpoints running on Vert.x.
     *
     * <p>This covers applications that use RESTEasy for business routes instead of
     * the Vert.x Router directly. The dispatch is synchronous, so
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
                            builder
                                    .visit(Advice.to(ResteasyDispatchAdvice.class)
                                            .on(named("invoke")
                                                    .and(takesArguments(2))
                                                    .and(takesArgument(0, named(
                                                            "org.jboss.resteasy.spi.HttpRequest")))
                                                    .and(takesArgument(1, named(
                                                            "org.jboss.resteasy.spi.HttpResponse")))))
                                    .visit(Advice.to(ResteasyWriteExceptionAdvice.class)
                                            .on(named("writeException")
                                                    .and(takesArgument(0, named(
                                                            "org.jboss.resteasy.spi.HttpRequest")))
                                                    .and(takesArgument(1, named(
                                                            "org.jboss.resteasy.spi.HttpResponse")))
                                                    .and(takesArgument(2, named(
                                                            "java.lang.Throwable"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: RESTEasy dispatcher instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: RESTEasy instrumentation skipped — "
                    + "resteasy not on classpath: {}", t.getMessage());
        }
    }

    /**
     * MySQL reactive (SqlClientBase): intercept {@code query(String)} and
     * {@code preparedQuery(String)} on the reactive SQL client implementation.
     *
     * <p>Also intercepts pool constructors (MySQLPoolImpl, PgPoolImpl) to capture
     * connection metadata (host, port, database) at creation time — a "capture at
     * creation" pattern for reliable db.name and net.peer.name extraction.
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
            log.warn("Vertx3Instrumenter: Reactive SQL instrumentation skipped — "
                    + "vertx-sql-client not on classpath: {}", t.getMessage());
        }

        // Capture connection metadata at pool creation time.
        // MySQLPoolImpl(ContextInternal, boolean, MySQLConnectOptions, PoolOptions)
        installReactiveSqlPoolMetadata(inst, listener,
                "io.vertx.mysqlclient.impl.MySQLPoolImpl", "MySQL");
        // PgPoolImpl(ContextInternal, boolean, PgConnectOptions, PoolOptions)
        installReactiveSqlPoolMetadata(inst, listener,
                "io.vertx.pgclient.impl.PgPoolImpl", "PostgreSQL");
    }

    private static void installReactiveSqlPoolMetadata(Instrumentation inst,
                                                        AgentBuilder.Listener listener,
                                                        String poolClassName,
                                                        String label) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named(poolClassName))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(ReactiveSqlPoolAdvice.class)
                                    .on(isConstructor()
                                            .and(takesArguments(4)))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: {} pool metadata capture installed", label);
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: {} pool metadata capture skipped — "
                    + "not on classpath: {}", label, t.getMessage());
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
            log.warn("Vertx3Instrumenter: Raw JDBC instrumentation skipped: {}", t.getMessage());
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
            log.warn("Vertx3Instrumenter: Jedis instrumentation skipped — "
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
            log.warn("Vertx3Instrumenter: Lettuce instrumentation skipped — "
                    + "lettuce-core not on classpath: {}", t.getMessage());
        }
    }

    /**
     * Vert.x HTTP Client: intercepts outgoing HTTP requests following the same
     * approach used by the OTel Java agent and Datadog.
     *
     * <p>Intercept strategy (mirrors OTel Java agent):
     * <ul>
     *   <li>Type matcher: {@code implementsInterface(HttpClientRequest)} — catches
     *       {@code HttpClientRequestImpl} and any other implementation without
     *       depending on the concrete class name.</li>
     *   <li>Send methods: all {@code end*} overloads + {@code sendHead} — in Vert.x 3,
     *       {@code end(Buffer)}, {@code end(String)}, etc. do NOT delegate to the
     *       no-arg {@code end()}, so all overloads must be intercepted independently
     *       to cover GET (no body) and POST/PUT (with body).</li>
     *   <li>Response/exception: {@code handleResponse} + {@code handleException} on
     *       {@code HttpClientRequestBase} to complete the span.</li>
     * </ul>
     *
     * <p>The {@code IN_HTTP_CLIENT_CALL} ThreadLocal guard (set by
     * {@link NettyHttpClientHelper#startSpan}) prevents duplicate CLIENT spans when
     * {@link io.last9.tracing.otel.v3.TracedWebClient} is also active.
     */
    private static void installNettyHttpClientInstrumentation(Instrumentation inst,
                                                                AgentBuilder.Listener listener) {
        try {
            // 1. Intercept all end() overloads + sendHead() on anything implementing
            //    HttpClientRequest (same approach as OTel Java agent's HttpRequestInstrumentation).
            //    named("end") matches all methods named exactly "end" — without also
            //    matching endHandler(). Adding sendHead() covers HTTP/1.1 streaming
            //    where headers are sent before the body.
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(hasSuperType(named("io.vertx.core.http.HttpClientRequest"))
                            .and(not(isInterface())))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(NettyHttpClientAdvice.class)
                                    .on(named("end").or(named("sendHead")))))
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

            log.info("Vertx3Instrumenter: HTTP client instrumentation installed " +
                    "(implementsInterface(HttpClientRequest), all end() + sendHead)");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: HTTP client instrumentation skipped: {}",
                    t.getMessage());
        }
    }

    /**
     * AWS SQS: intercept {@code receiveMessage(ReceiveMessageRequest)} on both
     * SDK v1 ({@code AmazonSQSClient}) and SDK v2 ({@code DefaultSqsClient})
     * to create CONSUMER spans for SQS message receive operations.
     */
    private static void installSqsInstrumentation(Instrumentation inst,
                                                    AgentBuilder.Listener listener) {
        // SDK v1: com.amazonaws.services.sqs.AmazonSQSClient
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("com.amazonaws.services.sqs.AmazonSQSClient"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(SqsReceiveAdvice.class)
                                    .on(named("receiveMessage")
                                            .and(takesArguments(1))
                                            .and(takesArgument(0, named(
                                                    "com.amazonaws.services.sqs.model.ReceiveMessageRequest"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: AWS SQS SDK v1 instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: AWS SQS SDK v1 instrumentation skipped — "
                    + "aws-java-sdk-sqs not on classpath: {}", t.getMessage());
        }

        // SDK v2: software.amazon.awssdk.services.sqs.DefaultSqsClient
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("software.amazon.awssdk.services.sqs.DefaultSqsClient"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(SqsReceiveAdvice.class)
                                    .on(named("receiveMessage")
                                            .and(takesArguments(1))
                                            .and(takesArgument(0, named(
                                                    "software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: AWS SQS SDK v2 instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: AWS SQS SDK v2 instrumentation skipped — "
                    + "sqs SDK v2 not on classpath: {}", t.getMessage());
        }
    }

    /**
     * Netty pipeline: intercept {@code DefaultChannelPipeline.addLast(String, ChannelHandler)}
     * to inject {@link NettyServerTracingHandler} when {@code HttpServerCodec} is added.
     *
     * <p>This creates SERVER spans at the Netty level — the same approach used by Datadog
     * and OTel Java agents. It serves as a safety net: even if
     * {@code HttpServerImpl.requestHandler()} instrumentation fails (e.g., classpath conflict),
     * the Netty handler still produces SERVER spans.
     */
    private static void installNettyServerPipelineInstrumentation(Instrumentation inst,
                                                                    AgentBuilder.Listener listener) {
        // Intercept ALL pipeline add methods (addLast, addFirst, addBefore, addAfter, replace)
        // matching the OTel Java agent approach. The last argument is always the ChannelHandler.
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.netty.channel.DefaultChannelPipeline"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder
                                    // addLast(String, ChannelHandler)
                                    .visit(Advice.to(NettyServerPipelineAdvice.class)
                                            .on(named("addLast")
                                                    .and(takesArguments(2))
                                                    .and(takesArgument(1, named("io.netty.channel.ChannelHandler")))))
                                    // addFirst(String, ChannelHandler)
                                    .visit(Advice.to(NettyServerPipelineAdvice.class)
                                            .on(named("addFirst")
                                                    .and(takesArguments(2))
                                                    .and(takesArgument(1, named("io.netty.channel.ChannelHandler")))))
                                    // addBefore(String, String, ChannelHandler)
                                    .visit(Advice.to(NettyServerPipelineBeforeAdvice.class)
                                            .on(named("addBefore")
                                                    .and(takesArguments(3))
                                                    .and(takesArgument(2, named("io.netty.channel.ChannelHandler")))))
                                    // addAfter(String, String, ChannelHandler)
                                    .visit(Advice.to(NettyServerPipelineBeforeAdvice.class)
                                            .on(named("addAfter")
                                                    .and(takesArguments(3))
                                                    .and(takesArgument(2, named("io.netty.channel.ChannelHandler")))))
                                    // replace(String, String, ChannelHandler)
                                    .visit(Advice.to(NettyServerPipelineBeforeAdvice.class)
                                            .on(named("replace")
                                                    .and(takesArguments(3))
                                                    .and(takesArgument(2, named("io.netty.channel.ChannelHandler"))))))
                    .installOn(inst);
            log.info("Vertx3Instrumenter: Netty pipeline instrumentation installed (all add/replace methods)");
        } catch (Throwable t) {
            log.warn("Vertx3Instrumenter: Netty pipeline instrumentation skipped: {}", t.getMessage());
        }
    }
}
