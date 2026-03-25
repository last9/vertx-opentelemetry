package io.last9.tracing.otel.v4.agent;

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
 * Installs ByteBuddy bytecode instrumentation for Vert.x 4 to enable zero-code
 * OpenTelemetry tracing.
 *
 * <p>Vert.x 4 has a {@code VertxTracer} SPI, so most tracing (HTTP server/client,
 * EventBus, SQL, Redis, Kafka) is handled natively once {@code TracingOptions} is set.
 * This instrumenter:
 * <ol>
 *   <li>Intercepts {@code VertxBuilder.init()} to inject {@code TracingOptions} and
 *       {@code MicrometerMetricsOptions} — enabling the SPI automatically</li>
 *   <li>Intercepts {@code Router.router(Vertx)} to add route-pattern span naming</li>
 *   <li>Installs library-level ByteBuddy advices for third-party libraries NOT covered
 *       by the SPI: Jedis, Lettuce, raw JDBC, Aerospike, RESTEasy, AWS SQS</li>
 * </ol>
 *
 * @see io.last9.tracing.otel.OtelAgent
 */
public final class Vertx4Instrumenter {

    private static final Logger log = LoggerFactory.getLogger(Vertx4Instrumenter.class);

    private Vertx4Instrumenter() {}

    /**
     * Install RxJava3 context propagation hooks and ByteBuddy class transformers.
     */
    public static void install(Instrumentation inst) {
        io.last9.tracing.otel.v4.RxJava3ContextPropagation.install();
        log.info("Vertx4Instrumenter: RxJava3 context propagation installed");

        installTransformersOnly(inst);
    }

    /**
     * Install only ByteBuddy class transformers, without RxJava hooks or OTel SDK init.
     *
     * <p>Used by the standalone agent ({@code vertx4-otel-agent}) which initializes
     * OTel SDK and RxJava hooks separately.
     */
    public static void installTransformersOnly(Instrumentation inst) {
        AgentBuilder.Listener listener = new AgentBuilder.Listener.Adapter() {
            @Override
            public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                         JavaModule module, boolean loaded, DynamicType dynamicType) {
                log.info("Vertx4Instrumenter: transformed {} (loaded={})",
                        typeDescription.getName(), loaded);
            }

            @Override
            public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                                boolean loaded, Throwable throwable) {
                log.warn("Vertx4Instrumenter: failed to transform {}: {}",
                        typeName, throwable.getMessage());
            }
        };

        // --- Vert.x 4 specific interceptors ---

        // VertxBuilder.init() → inject TracingOptions + MicrometerMetricsOptions
        installVertxBuilderInstrumentation(inst, listener);

        // Router.router(Vertx) → install SpanNameUpdater for route-pattern naming
        installRxRouterInstrumentation(inst, listener);

        // --- Library-agnostic interceptors (same as v3 agent) ---
        // These cover third-party libraries NOT handled by the VertxTracer SPI.

        installAerospikeInstrumentation(inst, listener);
        installRawJdbcInstrumentation(inst, listener);
        installJedisInstrumentation(inst, listener);
        installLettuceInstrumentation(inst, listener);
        installResteasyInstrumentation(inst, listener);
        installSqsInstrumentation(inst, listener);
    }

    /**
     * VertxBuilder.init() → inject TracingOptions and MicrometerMetricsOptions
     * into VertxOptions before the Vert.x instance is created.
     */
    private static void installVertxBuilderInstrumentation(Instrumentation inst,
                                                            AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.core.impl.VertxBuilder"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(VertxFactoryAdvice.class)
                                    .on(named("init")
                                            .and(takesArguments(0)))))
                    .installOn(inst);
            log.info("Vertx4Instrumenter: VertxBuilder instrumentation installed (TracingOptions + Metrics injection)");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: VertxBuilder instrumentation skipped: {}", t.getMessage());
        }
    }

    /**
     * Router.router(Vertx) → install SpanNameUpdater on returned Router (RxJava3 variant).
     */
    private static void installRxRouterInstrumentation(Instrumentation inst,
                                                        AgentBuilder.Listener listener) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.rxjava3.ext.web.Router"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(RxRouterAdvice.class)
                                    .on(isStatic()
                                            .and(named("router"))
                                            .and(takesArguments(1))
                                            .and(takesArgument(0,
                                                    named("io.vertx.rxjava3.core.Vertx"))))))
                    .installOn(inst);
            log.info("Vertx4Instrumenter: RxJava3 Router instrumentation installed (route-pattern span naming)");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: RxJava3 Router instrumentation skipped: {}", t.getMessage());
        }

        // Core Router variant
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener)
                    .disableClassFormatChanges()
                    .type(named("io.vertx.ext.web.Router"))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(RxRouterAdvice.class)
                                    .on(isStatic()
                                            .and(named("router"))
                                            .and(takesArguments(1))
                                            .and(takesArgument(0,
                                                    named("io.vertx.core.Vertx"))))))
                    .installOn(inst);
            log.info("Vertx4Instrumenter: Core Router instrumentation installed (route-pattern span naming)");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: Core Router instrumentation skipped: {}", t.getMessage());
        }
    }

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

            log.info("Vertx4Instrumenter: Aerospike instrumentation installed (single-key + batch)");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: Aerospike instrumentation skipped: {}", t.getMessage());
        }

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
            log.info("Vertx4Instrumenter: Aerospike SyncCommand instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: Aerospike SyncCommand skipped: {}", t.getMessage());
        }
    }

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
            log.info("Vertx4Instrumenter: Raw JDBC Statement instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: Raw JDBC instrumentation skipped: {}", t.getMessage());
        }
    }

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
            log.info("Vertx4Instrumenter: Jedis Redis instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: Jedis instrumentation skipped: {}", t.getMessage());
        }
    }

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
            log.info("Vertx4Instrumenter: Lettuce Redis instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: Lettuce instrumentation skipped: {}", t.getMessage());
        }
    }

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
            log.info("Vertx4Instrumenter: RESTEasy instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: RESTEasy instrumentation skipped: {}", t.getMessage());
        }
    }

    private static void installSqsInstrumentation(Instrumentation inst,
                                                    AgentBuilder.Listener listener) {
        // SDK v1
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
            log.info("Vertx4Instrumenter: AWS SQS SDK v1 instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: AWS SQS SDK v1 skipped: {}", t.getMessage());
        }

        // SDK v2
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
            log.info("Vertx4Instrumenter: AWS SQS SDK v2 instrumentation installed");
        } catch (Throwable t) {
            log.warn("Vertx4Instrumenter: AWS SQS SDK v2 skipped: {}", t.getMessage());
        }
    }
}
