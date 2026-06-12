# Vert.x OpenTelemetry

Drop a JAR on the JVM. Get traces, metrics, and logs. No code changes.

| Stack | Agent |
|-------|-------|
| Vert.x 3.9+ / RxJava 2 | `vertx3-otel-agent.jar` |
| Vert.x 4.5+ / RxJava 3 | `vertx4-otel-agent.jar` |

## Get started

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.last9.io
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <token>"

java -javaagent:vertx3-otel-agent.jar -jar my-app.jar
```

That's it. Every HTTP endpoint, database query, Kafka message, cache operation, and outbound HTTP call is traced automatically.

Download the latest agent from [Releases](https://github.com/last9/vertx-opentelemetry/releases):

```bash
# Vert.x 3
curl -L -o vertx3-otel-agent.jar \
  https://github.com/last9/vertx-opentelemetry/releases/download/v2.4.0/vertx3-otel-agent-2.4.0.jar

# Vert.x 4
curl -L -o vertx4-otel-agent.jar \
  https://github.com/last9/vertx-opentelemetry/releases/download/v2.4.0/vertx4-otel-agent-2.4.0.jar
```

## What gets instrumented

### Vert.x 3

ByteBuddy instruments these at class-load time — no wrappers, no annotations, no XML:

| Component | Span Kind | Key Attributes |
|-----------|-----------|---------------|
| Netty HTTP server | SERVER | `http.request.method`, `url.path`, `http.response.status_code` |
| Router (RxJava2 + core) | SERVER | `http.route` (pattern, not literal path) |
| WebClient | CLIENT | `url.full`, `server.address`, `http.response.status_code` |
| JDBCClient | CLIENT | `db.system`, `db.name`, `db.statement` |
| KafkaProducer | PRODUCER | `messaging.destination.name` |
| KafkaConsumer | CONSUMER | `messaging.batch.message_count` |
| AerospikeClient | CLIENT | `db.system=aerospike`, `net.peer.name` |
| MySQLPool / PgPool | CLIENT | `db.system`, `db.statement` |
| Jedis (Pool, Cluster, Pipeline) | CLIENT | `db.system=redis`, `db.statement` |
| Lettuce (sync/async/reactive) | CLIENT | `db.system=redis`, `db.statement` |
| Raw JDBC (`Statement.execute*`) | CLIENT | `db.system` (auto-detected), `db.statement` |
| Netty HTTP client | CLIENT | `http.method`, `net.peer.name` |
| RESTEasy (JAX-RS) | — | `@Path` templates → `http.route`; async `CompletionStage` handlers get correct route, response body, and ERROR status + exception (incl. servlet/Undertow) |
| AWS SQS (SDK v1 + v2) | CONSUMER | `messaging.system=AmazonSQS` |

### Vert.x 4

The agent injects `TracingOptions` at startup, activating the native `VertxTracer` SPI. No ByteBuddy needed for the core stack:

| Component | How | What you get |
|-----------|-----|-------------|
| HTTP server | VertxTracer SPI | SERVER spans for every request |
| HTTP client | VertxTracer SPI | CLIENT spans + `traceparent` injection |
| EventBus | VertxTracer SPI | INTERNAL spans for send/publish |
| SQL client (PgPool, MySQLPool) | VertxTracer SPI | CLIENT spans with SQL |
| Redis client | VertxTracer SPI | CLIENT spans |
| Kafka | VertxTracer SPI | PRODUCER/CONSUMER spans |
| Router | ByteBuddy | Route patterns: `GET /v1/users/:id` |
| Vert.x metrics | Micrometer → OTel bridge | HTTP pools, event bus, event loop lag |
| Jedis, Lettuce, JDBC, Aerospike, RESTEasy, SQS | ByteBuddy | Same as v3 |

### Across all agents

- **RxJava context propagation** — trace context flows across `subscribeOn`, `observeOn`, `flatMap`
- **W3C `traceparent`** — injected on every outgoing HTTP and Kafka call
- **Log-trace correlation** — `trace_id` and `span_id` auto-injected into every Logback line. No `logback.xml` changes.
- **JVM metrics** — memory, GC, threads, CPU
- **Cloud resource detection** — AWS (EC2, ECS, EKS) and GCP (GCE, GKE, Cloud Run)

## Why not the OpenTelemetry Java Agent?

The OTel Java Agent assumes `ThreadLocal` context propagation. Vert.x doesn't use `ThreadLocal`. The result: broken spans on async HTTP calls ([#11860](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/11860)), lost context across RxJava operators, and broken virtual thread support on Java 21 ([#10526](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/10526)).

This library works with Vert.x's event-loop model — handler-based instrumentation for v3, native `VertxTracer` SPI for v4, RxJava assembly hooks for context propagation across all operators.

## Java compatibility

**Java 8+.** The agent uses the OkHttp-based OTLP sender (shaded as `io.last9.internal.okhttp3`) instead of `java.net.http.HttpClient`. Same JAR on Java 8, 11, 17, 21.

## Deployment

### EC2 / Bare metal

```bash
curl -L -o /opt/otel/vertx3-otel-agent.jar \
  https://github.com/last9/vertx-opentelemetry/releases/download/v2.4.0/vertx3-otel-agent-2.4.0.jar

java -javaagent:/opt/otel/vertx3-otel-agent.jar -jar /opt/app/my-app.jar
```

### Docker / Kubernetes

```dockerfile
FROM eclipse-temurin:11-jre-alpine
COPY target/my-app.jar /app/my-app.jar
COPY vertx3-otel-agent.jar /app/vertx3-otel-agent.jar
CMD ["java", "-javaagent:/app/vertx3-otel-agent.jar", "-jar", "/app/my-app.jar"]
```

## How it works

1. A 2-class shim (`AgentBootstrap`) loads on the system classloader — compiled to Java 8 bytecode
2. The embedded library JAR (`agent-impl.jar`) is injected onto the system classloader
3. All OTel SDK, ByteBuddy, and OkHttp classes are shaded under `io.last9.internal.*` — no classpath conflicts even if your app bundles its own OTel SDK
4. ByteBuddy transformers intercept Vert.x APIs at load time
5. RxJava context propagation hooks are installed automatically
6. Log-trace correlation is auto-installed into Logback

**No Maven dependency required.** Drop the JAR and go.

> **Do not add `io.last9:vertx3-rxjava2-otel-autoconfigure` (or the v4 equivalent) as a Maven dependency when using `-javaagent`.** The app's unshaded classes will shadow the agent's shaded ones — you'll get a no-op tracer and zero spans.

## Configuration

All standard [OpenTelemetry environment variables](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/) work:

| Variable | Default |
|----------|---------|
| `OTEL_SERVICE_NAME` | `unknown-service` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` |
| `OTEL_EXPORTER_OTLP_HEADERS` | — |
| `OTEL_EXPORTER_OTLP_TIMEOUT` | `10000` |
| `OTEL_RESOURCE_ATTRIBUTES` | — |
| `OTEL_TRACES_SAMPLER` | `parentbased_always_on` |
| `OTEL_LOGS_EXPORTER` | `otlp` |
| `OTEL_METRICS_EXPORTER` | `otlp` |
| `OTEL_METRIC_EXPORT_INTERVAL` | `60000` |
| `OTEL_BSP_SCHEDULE_DELAY` | `5000` |

Set `OTEL_EXPORTER_OTLP_TIMEOUT=30000` when exporting to remote backends.

## HTTP body capture

Disabled by default. Set `VERTX_OTEL_BODY_CAPTURE_ENABLED=true` to start recording request and response bodies as span attributes.

```bash
VERTX_OTEL_BODY_CAPTURE_ENABLED=true java -javaagent:vertx3-otel-agent.jar -jar my-app.jar
```

When enabled, every HTTP SERVER span gets:

| Span attribute | Example |
|----------------|---------|
| `http.request.body` | `{"userId":"u1","symbol":"AAPL","quantity":10}` |
| `http.response.body` | `{"id":42,"userId":"u1","symbol":"AAPL"}` |

Only `application/json`, `application/xml`, and `text/*` content types are captured by default — binary payloads are skipped.

### Tuning

| Variable | Default | Description |
|----------|---------|-------------|
| `VERTX_OTEL_BODY_CAPTURE_ENABLED` | `false` | Master switch |
| `VERTX_OTEL_BODY_CAPTURE_REQUEST` | `true` | Capture request bodies |
| `VERTX_OTEL_BODY_CAPTURE_RESPONSE` | `true` | Capture response bodies |
| `VERTX_OTEL_BODY_CAPTURE_MAX_BYTES` | `8192` | Truncate bodies longer than this |
| `VERTX_OTEL_BODY_CAPTURE_ERROR_ONLY` | `false` | Only capture on 4xx/5xx responses |
| `VERTX_OTEL_BODY_CAPTURE_CONTENT_TYPES` | `application/json,application/xml,text/` | Comma-separated prefix allowlist |
| `VERTX_OTEL_BODY_CAPTURE_INCLUDE_PATHS` | — | Only capture on these path prefixes (empty = all) |
| `VERTX_OTEL_BODY_CAPTURE_EXCLUDE_PATHS` | — | Skip capture on these path prefixes |

**Example: capture only POST /v1/payments, skip /health:**

```bash
VERTX_OTEL_BODY_CAPTURE_ENABLED=true
VERTX_OTEL_BODY_CAPTURE_INCLUDE_PATHS=/v1/payments
VERTX_OTEL_BODY_CAPTURE_EXCLUDE_PATHS=/health
```

### Prerequisite for Vert.x 3 POST/PUT routes

The agent captures the body that Vert.x has already buffered. For POST/PUT routes, `BodyHandler` must be registered before your route handlers — otherwise the body is not buffered and nothing is captured:

```java
router.route().handler(BodyHandler.create());
router.post("/v1/orders").handler(this::handleCreateOrder);
```

This is standard Vert.x — `BodyHandler` is required to call `ctx.getBodyAsJson()` or `ctx.body()` in your handlers regardless of body capture.

### Security note

Body capture records raw payloads in your trace backend. Use `VERTX_OTEL_BODY_CAPTURE_EXCLUDE_PATHS` or `VERTX_OTEL_BODY_CAPTURE_ERROR_ONLY` to avoid capturing auth endpoints or PII-heavy routes in production.

## Troubleshooting

**Disconnected traces (outgoing calls show as separate root spans)**

1. Look for `bytecode instrumentation installed` in logs — confirms the agent loaded
2. Look for `RxJava context propagation installed` — confirms async context flows
3. Verify the downstream service is also OTel-instrumented and reads `traceparent`

**No spans exported**

1. Check `OTEL_EXPORTER_OTLP_ENDPOINT` is reachable
2. Check `OTEL_EXPORTER_OTLP_HEADERS` auth is correct
3. Look for `Connection refused` in stderr
4. Set `OTEL_LOG_LEVEL=debug` for verbose export logs

**CLIENT spans present but no SERVER spans**

1. Look for `transformed io.vertx.reactivex.ext.web.Router (loaded=false)` and `HttpServerAdviceHelper: wrapping requestHandler` in logs
2. Look for `Tracer OK (io.last9.internal.otel.sdk.trace.SdkTracer)` on stderr — if you see `Tracer is NO-OP`, SDK init failed
3. Look for `WARNING: HttpServerAdviceHelper is missing version marker` — this means the app bundles a conflicting `io.last9` library dependency. Remove it.

## Requirements

| Agent | Java | Vert.x | RxJava |
|-------|------|--------|--------|
| `vertx3-otel-agent` | 8+ | 3.9+ | 2.x |
| `vertx4-otel-agent` | 11+ | 4.5+ | 3.x |

---

<details>
<summary><h2>Library Mode</h2></summary>

> Use the agent. Library mode exists for environments where `-javaagent` is not feasible.

### Vert.x 4

```xml
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx4-rxjava3-otel-autoconfigure</artifactId>
    <version>2.4.0</version>
</dependency>
```

```java
// Use OtelLauncher as main class
// <mainClass>io.last9.tracing.otel.v4.OtelLauncher</mainClass>

Router router = TracedRouter.create(vertx);
TracedDBPool traced = TracedDBPool.wrap(PgPool.pool(vertx, opts, poolOpts), "postgresql", "mydb");
consumer.batchHandler(KafkaTracing.tracedBatchHandler(topicName, this::handleBatch));
```

### Vert.x 3

```xml
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx3-rxjava2-otel-autoconfigure</artifactId>
    <version>2.4.0</version>
</dependency>
```

```java
// <mainClass>io.last9.tracing.otel.v3.OtelLauncher</mainClass>

Router router = TracedRouter.create(vertx);
WebClient client = TracedWebClient.create(vertx);
SQLClient sql = TracedSQLClient.wrap(JDBCClient.createShared(vertx, config), "mysql", "mydb");
TracedAerospikeClient aero = TracedAerospikeClient.wrap(new AerospikeClient("localhost", 3000), "ns");
TracedKafkaProducer<String, String> producer = TracedKafkaProducer.wrap(KafkaProducer.create(vertx, config));
TracedKafkaConsumer.create(vertx, config, "topic", "group", records -> { ... });
```

### Manual SDK init (custom main class)

```java
import io.last9.tracing.otel.OtelSdkSetup;
import io.last9.tracing.otel.v3.RxJava2ContextPropagation; // or v4.RxJava3ContextPropagation

OtelSdkSetup.initialize();
RxJava2ContextPropagation.install();
```

</details>

## License

MIT

## Support

- [GitHub Issues](https://github.com/last9/vertx-opentelemetry/issues)
- [Last9 Documentation](https://last9.io/docs)
