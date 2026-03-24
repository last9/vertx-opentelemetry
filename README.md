# Vert.x OpenTelemetry Auto-Configure

Zero-code OpenTelemetry instrumentation for Vert.x applications. Add `-javaagent:vertx3-otel-agent.jar` to your JVM args — no code changes, no dependencies, no `TracedRouter` swaps. The agent handles everything.

| Your Stack | Approach |
|------------|----------|
| **Vert.x 3.9+ / RxJava 2** | `-javaagent:vertx3-otel-agent.jar` (zero-code) |
| Vert.x 4.5+ / RxJava 3 | `TracedRouter.create(vertx)` + env vars |

## Quick Start (Vert.x 3 — zero-code)

### 1. Get the agent

Download from [Releases](https://github.com/last9/vertx-opentelemetry/releases):

```bash
curl -L -o vertx3-otel-agent.jar \
  https://github.com/last9/vertx-opentelemetry/releases/download/v2.2.2/vertx3-otel-agent-2.2.2.jar
```

### 2. Run your app with the agent

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.last9.io
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <token>"

java -javaagent:vertx3-otel-agent.jar -jar my-app.jar
```

That's it. Every Router endpoint, JDBC query, Kafka message, Aerospike operation, Redis command, and outbound HTTP call is automatically traced.

```
┌──────────────────────────────────────────────────────────┐
│  java -javaagent:vertx3-otel-agent.jar -jar my-app.jar  │
│                                                          │
│  Agent auto-instruments at bytecode level:               │
│  ├── Router          → SERVER spans (HTTP routes)        │
│  ├── WebClient       → CLIENT spans + traceparent        │
│  ├── JDBCClient      → CLIENT spans (SQL queries)        │
│  ├── KafkaProducer   → PRODUCER spans                    │
│  ├── KafkaConsumer   → CONSUMER spans                    │
│  ├── AerospikeClient → CLIENT spans (cache ops)          │
│  ├── MySQLPool       → CLIENT spans (reactive SQL)       │
│  ├── PgPool          → CLIENT spans (reactive SQL)       │
│  ├── Jedis           → CLIENT spans (Redis)              │
│  ├── Lettuce         → CLIENT spans (Redis)              │
│  ├── Netty HTTP      → CLIENT spans (raw HTTP)           │
│  ├── RESTEasy        → route-pattern extraction          │
│  └── Raw JDBC        → CLIENT spans (any JDBC driver)    │
│                                                          │
│  + RxJava2 context propagation across all operators      │
│  + W3C traceparent propagation (distributed tracing)     │
│  + Log-trace correlation (auto-installed into Logback)   │
│  + JVM metrics (memory, GC, threads, CPU)                │
│  + Exports via OkHttp OTLP sender (Java 8+ compatible)   │
└──────────────────────────────────────────────────────────┘
```

### 3. Verify traces

You'll see in your application logs:

```
=== OpenTelemetry Auto-Configuration ===
Service: my-service
OTLP Endpoint: https://otlp.last9.io
OpenTelemetry SDK initialized successfully
W3C trace context propagation configured (traceparent header enabled)
Logback OpenTelemetry appender installed for log export
=== OpenTelemetry Ready ===
```

## What Gets Auto-Instrumented

### Vert.x 3 (via `-javaagent`)

No code changes or `Traced*` wrappers needed. ByteBuddy instruments these at class-load time:

| Component | Span Kind | Attributes |
|-----------|-----------|------------|
| **Router** (RxJava2 + core) | SERVER | `http.method`, `http.route`, `http.status_code` |
| **WebClient** | CLIENT | `http.request.method`, `url.full`, `server.address`, `http.response.status_code` |
| **JDBCClient** (any JDBC driver) | CLIENT | `db.system`, `db.name`, `db.statement` |
| **KafkaProducer** | PRODUCER | `messaging.system=kafka`, `messaging.destination.name`, `messaging.operation` |
| **KafkaConsumer** | CONSUMER | `messaging.system=kafka`, `messaging.destination.name`, `messaging.batch.message_count` |
| **AerospikeClient** (single + batch) | CLIENT | `db.system=aerospike`, `db.name`, `db.operation` |
| **MySQLPool / PgPool** (reactive SQL) | CLIENT | `db.system`, `db.name`, `db.statement`, `net.peer.name` |
| **Jedis** (Pool, Cluster, Pipeline) | CLIENT | `db.system=redis`, `db.statement` |
| **Lettuce** (sync/async/reactive) | CLIENT | `db.system=redis`, `db.statement` |
| **Raw JDBC** (`Statement.execute*`) | CLIENT | `db.system` (auto-detected), `db.name`, `db.statement` |
| **Netty HTTP client** | CLIENT | `http.method`, `net.peer.name` |
| **RESTEasy (JAX-RS)** | — | Extracts `@Path` templates → `http.route` |

### Vert.x 4

Vert.x 4's `VertxTracer` SPI handles HTTP server/client spans automatically. Add `TracedRouter` for route-pattern span names:

```java
import io.last9.tracing.otel.v4.TracedRouter;
Router router = TracedRouter.create(vertx);  // "GET /v1/users/:id" instead of just "GET"
```

For database tracing, wrap your pool:

```java
import io.last9.tracing.otel.v4.TracedDBPool;
TracedDBPool traced = TracedDBPool.wrap(PgPool.pool(vertx, opts, poolOpts), "postgresql", "mydb");
traced.query("SELECT * FROM orders").subscribe(rows -> { ... });
```

## Java 8 Support

**Full instrumentation works on Java 8+.** The entire stack — OTel SDK, ByteBuddy, OkHttp OTLP sender — supports Java 8.

```bash
# Works on Java 8, 11, 17, 21 — same JAR, same command
java -javaagent:vertx3-otel-agent.jar -jar my-app.jar
```

The agent uses the OkHttp-based OTLP sender (shaded as `io.last9.internal.okhttp3`) instead of the JDK HttpClient sender, making it compatible with Java 8 JVMs where `java.net.http.HttpClient` is not available.

## Deployment

### EC2 / Bare metal

```bash
# Download once (bake into AMI or fetch at startup)
curl -L -o /opt/otel/vertx3-otel-agent.jar \
  https://github.com/last9/vertx-opentelemetry/releases/download/v2.2.2/vertx3-otel-agent-2.2.2.jar

# Add to your systemd unit or startup script
java -javaagent:/opt/otel/vertx3-otel-agent.jar -jar /opt/app/my-app.jar
```

### Docker / Kubernetes

```dockerfile
FROM eclipse-temurin:11-jre-alpine
COPY target/my-app.jar /app/my-app.jar
COPY vertx3-otel-agent.jar /app/vertx3-otel-agent.jar
CMD ["java", "-javaagent:/app/vertx3-otel-agent.jar", "-jar", "/app/my-app.jar"]
```

### How the agent works

The agent uses classloader isolation (like the OTel Java Agent):

1. A tiny 2-class shim (`AgentBootstrap`) loads on the system classloader — compiled to Java 8 bytecode for version-check safety
2. All heavy dependencies (ByteBuddy, OTel SDK, OkHttp) load in an isolated classloader from an embedded JAR (`agent-impl.jar`)
3. ByteBuddy class transformers intercept Vert.x APIs at load time
4. RxJava2 context propagation hooks are installed automatically
5. Log-trace correlation (`MdcTraceTurboFilter` + `OpenTelemetryAppender`) is auto-installed into Logback — no `logback.xml` changes needed

**No Maven dependency required.** The agent is fully self-contained. Your app doesn't need any `io.last9` dependency in `pom.xml`.

## Configuration

All standard [OpenTelemetry environment variables](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/) are supported:

| Variable | Description | Default |
|----------|-------------|---------|
| `OTEL_SERVICE_NAME` | Service name in traces | `unknown-service` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP endpoint URL | `http://localhost:4318` |
| `OTEL_EXPORTER_OTLP_HEADERS` | Auth headers (e.g. `Authorization=Basic ...`) | — |
| `OTEL_EXPORTER_OTLP_TIMEOUT` | HTTP timeout per export (ms) | `10000` |
| `OTEL_RESOURCE_ATTRIBUTES` | Extra resource attributes | — |
| `OTEL_TRACES_SAMPLER` | Sampling strategy | `parentbased_always_on` |
| `OTEL_LOGS_EXPORTER` | Log exporter (`otlp` / `none`) | `otlp` |
| `OTEL_METRICS_EXPORTER` | Metrics exporter (`otlp` / `none`) | `otlp` |
| `OTEL_METRIC_EXPORT_INTERVAL` | Metrics push interval (ms) | `60000` |
| `OTEL_BSP_SCHEDULE_DELAY` | Span batch export interval (ms) | `5000` |

> **Tip:** Set `OTEL_EXPORTER_OTLP_TIMEOUT=30000` when exporting to remote backends to avoid timeouts on the first metrics export.

## What You Get

### Tracing
- **SERVER spans** for every incoming request with method, route pattern, status code
- **CLIENT spans** for every outgoing HTTP, database, and cache operation
- **PRODUCER/CONSUMER spans** for Kafka with W3C `traceparent` propagation through headers
- **Route-pattern span names** like `GET /v1/users/:id` (not `GET /v1/users/42`)
- **Distributed tracing** via W3C `traceparent` header — auto-injected on all outgoing calls
- **RxJava context propagation** — trace context flows across `subscribeOn`, `observeOn`, `flatMap`
- **Exception events** — when a handler calls `ctx.fail(throwable)`, recorded as span events
- **DB span naming** — `{OPERATION} {db.name}.{table}` convention (e.g., `SELECT holdingdb.users`)

### Metrics
- **JVM metrics** — `jvm.memory.used`, `jvm.gc.duration`, `jvm.thread.count`, `jvm.cpu.time`
- **Process/host attributes** — `process.pid`, `host.name`, `os.type` on every span
- **Cloud resource detection** — AWS (EC2, ECS, EKS) and GCP (GCE, GKE, Cloud Run) auto-detected
- **Vert.x 4 internal metrics** — HTTP connection pools, event bus, event loop lag (via Micrometer bridge)

### Logging
- **Auto log-trace correlation** — `trace_id` and `span_id` injected into every log line via `MdcTraceTurboFilter`
- **OTLP log export** — logs sent alongside traces with trace context attached
- **No logback.xml changes** — both the MDC filter and OTel appender are auto-installed at runtime

## Troubleshooting

### Disconnected Traces

If outgoing calls show as separate root traces:

1. **Check the agent loaded** — look for `Vertx3Instrumenter: bytecode instrumentation installed` in logs
2. **Check RxJava context propagation** — look for `RxJava2 OpenTelemetry context propagation hooks installed`
3. **Check downstream reads `traceparent`** — the downstream service must be OTel-instrumented
4. **Verify `rxSend()` runs inside a handler** — trace context is only available within a Router handler chain. Calls from timers or EventBus consumers need their own span context.

### No Spans Exported

1. Check `OTEL_EXPORTER_OTLP_ENDPOINT` is set and reachable
2. Check `OTEL_EXPORTER_OTLP_HEADERS` has correct auth
3. Look for `Connection refused` errors in stderr (OkHttp sender logs export failures)
4. Set `OTEL_LOG_LEVEL=debug` for verbose export logging

## Why Not the OTel Java Agent?

The standard OpenTelemetry Java Agent assumes `ThreadLocal`-based context propagation, which breaks with Vert.x's event-loop model:

- Trace context lost after async HTTP client calls ([#11860](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/11860))
- Broken spans with virtual threads on Java 21 ([#10526](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/10526))
- RxJava operators lose trace context across thread hops

This library works *with* Vert.x's context model — using handler-based instrumentation (v3) or the native `VertxTracer` SPI (v4), with RxJava assembly hooks to propagate context across all operators.

## Pre-release / Beta Builds

Every push and PR builds JARs as GitHub Actions artifacts. Go to [Actions](https://github.com/last9/vertx-opentelemetry/actions/workflows/ci.yaml), click a run, and download the `jars-<sha>` artifact. Install locally:

```bash
mvn install:install-file -Dfile=vertx3-otel-agent-<version>.jar \
  -DgroupId=io.last9 -DartifactId=vertx3-otel-agent -Dversion=<version> -Dpackaging=jar
```

Tagged pre-releases appear on the [Releases](https://github.com/last9/vertx-opentelemetry/releases) page with downloadable JARs.

## Requirements

| Module | Java | Vert.x | RxJava |
|--------|------|--------|--------|
| `vertx3-otel-agent` (agent JAR) | **8+** | 3.9+ | 2.x |
| `vertx4-rxjava3-otel-autoconfigure` | 11+ | 4.5+ | 3.x |
| `vertx3-rxjava2-otel-autoconfigure` | 11+ | 3.9+ | 2.x |

The standalone agent works on both **JDK and JRE** with full classloader isolation.

---

<details>
<summary><h2>Vert.x 4: TracedRouter + TracedDBPool</h2></summary>

Vert.x 4's `VertxTracer` SPI handles HTTP server/client spans and Kafka producer/consumer automatically. You need `TracedRouter` for route-pattern names and `TracedDBPool` for database spans.

### Add the dependency

```xml
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx4-rxjava3-otel-autoconfigure</artifactId>
    <version>2.2.2</version>
</dependency>
```

### TracedRouter

```java
import io.last9.tracing.otel.v4.TracedRouter;
Router router = TracedRouter.create(vertx);
```

### TracedDBPool

```java
import io.last9.tracing.otel.v4.TracedDBPool;

// PostgreSQL
TracedDBPool traced = TracedDBPool.wrap(PgPool.pool(vertx, opts, poolOpts), "postgresql", "mydb");
traced.query("SELECT * FROM orders").subscribe(rows -> { ... });
traced.preparedQuery("SELECT * FROM orders WHERE id = $1", Tuple.of(42)).subscribe(...);

// MySQL
TracedDBPool mysql = TracedDBPool.wrap(MySQLPool.pool(vertx, opts, poolOpts), "mysql", "mydb");
```

### Kafka batch consumer

```java
import io.last9.tracing.otel.v4.KafkaTracing;
consumer.batchHandler(KafkaTracing.tracedBatchHandler(topicName, this::handleBatch));
```

### Metrics

`OtelLauncher` automatically configures a Micrometer → OpenTelemetry bridge for Vert.x internal metrics (HTTP pools, event bus, event loop lag). Set `OTEL_METRICS_EXPORTER=otlp` to export.

</details>

<details>
<summary><h2>Legacy: Manual Traced* Wrappers (Vert.x 3)</h2></summary>

> **Prefer the `-javaagent` approach.** Manual wrappers are for environments where `-javaagent` is not feasible.

### Add the dependency

```xml
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx3-rxjava2-otel-autoconfigure</artifactId>
    <version>2.2.2</version>
</dependency>
```

### OtelLauncher (alternative to `-javaagent`)

Set as your main class — it self-attaches ByteBuddy before deploying verticles. Requires a **JDK** runtime (not JRE).

```xml
<mainClass>io.last9.tracing.otel.v3.OtelLauncher</mainClass>
```

If both `-javaagent` and OtelLauncher are used, OtelLauncher detects the agent already ran and becomes a no-op.

### Manual wrapper APIs

For environments where neither `-javaagent` nor `OtelLauncher` is feasible:

```java
// Router
import io.last9.tracing.otel.v3.TracedRouter;
Router router = TracedRouter.create(vertx);

// WebClient
import io.last9.tracing.otel.v3.TracedWebClient;
WebClient client = TracedWebClient.create(vertx);

// SQL (legacy SQLClient)
import io.last9.tracing.otel.v3.TracedSQLClient;
SQLClient client = TracedSQLClient.wrap(JDBCClient.createShared(vertx, config), "mysql", "mydb");

// MySQL (reactive)
import io.last9.tracing.otel.v3.TracedMySQLClient;
TracedMySQLClient mysql = TracedMySQLClient.wrap(MySQLPool.pool(vertx, opts, poolOpts), "mydb");

// Redis
import io.last9.tracing.otel.v3.TracedRedisClient;
RedisAPI redis = TracedRedisClient.wrap(RedisAPI.api(connection), "0");

// Aerospike
import io.last9.tracing.otel.v3.TracedAerospikeClient;
TracedAerospikeClient client = TracedAerospikeClient.wrap(new AerospikeClient("localhost", 3000), "ns");

// Kafka Producer
import io.last9.tracing.otel.v3.TracedKafkaProducer;
TracedKafkaProducer<String, String> producer = TracedKafkaProducer.wrap(KafkaProducer.create(vertx, config));

// Kafka Consumer
import io.last9.tracing.otel.v3.TracedKafkaConsumer;
TracedKafkaConsumer.create(vertx, config, "topic", "group", records -> { ... });

// Worker thread context propagation
import io.last9.tracing.otel.v3.TracedVertx;
TracedVertx.<Record>rxExecuteBlocking(vertx, promise -> { ... });

// Generic RxJava2 client wrapping
import io.last9.tracing.otel.v3.TracedRxClient;
MyClient traced = TracedRxClient.wrap(client, MyClient.class, "mysql", "mydb");

// Per-request HTTP tracing
import io.last9.tracing.otel.v3.ClientTracing;
ClientTracing.traced(webClient.getAbs(url)).rxSend().subscribe(...);
```

### Manual SDK initialization

If using a custom main class (not OtelLauncher):

```java
import io.last9.tracing.otel.OtelSdkSetup;
import io.last9.tracing.otel.v3.RxJava2ContextPropagation;

OtelSdkSetup.initialize();
RxJava2ContextPropagation.install();
```

</details>

## License

MIT

## Support

- [GitHub Issues](https://github.com/last9/vertx-opentelemetry/issues)
- [Last9 Documentation](https://last9.io/docs)
