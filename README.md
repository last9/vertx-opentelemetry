# Vert.x OpenTelemetry Auto-Configure

Zero-code OpenTelemetry instrumentation for Vert.x applications. Add `-javaagent` to your JVM args — no code changes, no dependencies. The agent handles everything.

| Your Stack | Agent JAR | Approach |
|------------|-----------|----------|
| **Vert.x 3.9+ / RxJava 2** | `vertx3-otel-agent.jar` | Zero-code via `-javaagent` |
| **Vert.x 4.5+ / RxJava 3** | `vertx4-otel-agent.jar` | Zero-code via `-javaagent` |

## Quick Start

### 1. Get the agent

Download from [Releases](https://github.com/last9/vertx-opentelemetry/releases):

```bash
# Vert.x 3
curl -L -o vertx3-otel-agent.jar \
  https://github.com/last9/vertx-opentelemetry/releases/download/v2.2.3/vertx3-otel-agent-2.2.3.jar

# Vert.x 4
curl -L -o vertx4-otel-agent.jar \
  https://github.com/last9/vertx-opentelemetry/releases/download/v2.2.3/vertx4-otel-agent-2.2.3.jar
```

### 2. Run your app with the agent

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.last9.io
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <token>"

# Vert.x 3
java -javaagent:vertx3-otel-agent.jar -jar my-app.jar

# Vert.x 4
java -javaagent:vertx4-otel-agent.jar -jar my-app.jar
```

That's it. Every HTTP endpoint, database query, Kafka message, cache operation, and outbound HTTP call is automatically traced.

### What the agent auto-instruments

```
┌──────────────────────────────────────────────────────────────┐
│  java -javaagent:vertx-otel-agent.jar -jar my-app.jar        │
│                                                              │
│  Vert.x 3 agent (ByteBuddy)     Vert.x 4 agent (SPI + BB)  │
│  ├── Netty HTTP   → SERVER      ├── HTTP server  → SERVER   │
│  ├── Router       → SERVER      ├── HTTP client  → CLIENT   │
│  ├── WebClient    → CLIENT      ├── EventBus     → INTERNAL │
│  ├── KafkaProducer→ PRODUCER    ├── SQL client   → CLIENT   │
│  ├── KafkaConsumer→ CONSUMER    ├── Redis client → CLIENT   │
│  ├── Netty client → CLIENT      ├── Kafka        → PROD/CON │
│  ├── MySQLPool    → CLIENT      ├── Router names → http.route│
│  ├── PgPool       → CLIENT      └── Micrometer   → metrics  │
│  ├── JDBCClient   → CLIENT                                  │
│  ├── Raw JDBC     → CLIENT      Shared (both agents):       │
│  ├── Jedis        → CLIENT      ├── Jedis        → CLIENT   │
│  ├── Lettuce      → CLIENT      ├── Lettuce      → CLIENT   │
│  ├── Aerospike    → CLIENT      ├── Raw JDBC     → CLIENT   │
│  ├── RESTEasy     → http.route  ├── Aerospike    → CLIENT   │
│  └── AWS SQS      → CONSUMER   ├── RESTEasy     → http.route│
│  + RxJava 2/3 context propagation across all operators       │
│  + W3C traceparent propagation (distributed tracing)         │
│  + Log-trace correlation (auto-installed into Logback)       │
│  + JVM metrics (memory, GC, threads, CPU)                    │
│  + OTel namespace fully shaded — safe with any app classpath │
└──────────────────────────────────────────────────────────────┘
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

### Vert.x 3 (via `-javaagent:vertx3-otel-agent.jar`)

No code changes or `Traced*` wrappers needed. ByteBuddy instruments these at class-load time:

| Component | Span Kind | Attributes |
|-----------|-----------|------------|
| **Netty HTTP server** (all requests) | SERVER | `http.request.method`, `url.path`, `http.response.status_code`, `server.address` |
| **Router** (RxJava2 + core) | SERVER | `http.method`, `http.route`, `http.status_code` |
| **WebClient** | CLIENT | `http.request.method`, `url.full`, `server.address`, `http.response.status_code` |
| **JDBCClient** (any JDBC driver) | CLIENT | `db.system`, `db.name`, `db.statement` |
| **KafkaProducer** | PRODUCER | `messaging.system=kafka`, `messaging.destination.name`, `messaging.operation` |
| **KafkaConsumer** | CONSUMER | `messaging.system=kafka`, `messaging.destination.name`, `messaging.batch.message_count` |
| **AerospikeClient** (sync + batch + async) | CLIENT | `db.system=aerospike`, `db.name`, `net.peer.name`, `net.peer.port` |
| **MySQLPool / PgPool** (reactive SQL) | CLIENT | `db.system`, `db.name`, `db.statement`, `net.peer.name` |
| **Jedis** (Pool, Cluster, Pipeline) | CLIENT | `db.system=redis`, `db.statement` |
| **Lettuce** (sync/async/reactive) | CLIENT | `db.system=redis`, `db.statement` |
| **Raw JDBC** (`Statement.execute*`) | CLIENT | `db.system` (auto-detected), `db.name`, `db.statement` |
| **Netty HTTP client** | CLIENT | `http.method`, `net.peer.name` |
| **RESTEasy (JAX-RS)** | — | Extracts `@Path` templates → `http.route` |
| **AWS SQS** (SDK v1 + v2) | CONSUMER | `messaging.system=AmazonSQS`, `messaging.destination.name` |

### Vert.x 4 (via `-javaagent:vertx4-otel-agent.jar`)

Also zero-code. The agent injects `TracingOptions` into Vert.x at startup, enabling the native `VertxTracer` SPI:

| Component | How it works | What you get |
|-----------|-------------|-------------|
| **HTTP server** | VertxTracer SPI (automatic) | SERVER spans for every request |
| **HTTP client** | VertxTracer SPI (automatic) | CLIENT spans + `traceparent` injection |
| **EventBus** | VertxTracer SPI (automatic) | INTERNAL spans for send/publish |
| **SQL client** (PgPool, MySQLPool) | VertxTracer SPI (automatic) | CLIENT spans with SQL statements |
| **Redis client** | VertxTracer SPI (automatic) | CLIENT spans |
| **Kafka producer/consumer** | VertxTracer SPI (automatic) | PRODUCER/CONSUMER spans |
| **Router** | ByteBuddy (SpanNameUpdater) | Route-pattern names: `GET /v1/users/:id` |
| **Vert.x metrics** | Micrometer → OTel bridge (automatic) | HTTP pools, event bus, event loop lag |
| **Jedis, Lettuce, JDBC, Aerospike, RESTEasy, SQS** | ByteBuddy (same as v3) | CLIENT spans for third-party libs |

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
  https://github.com/last9/vertx-opentelemetry/releases/download/v2.2.3/vertx3-otel-agent-2.2.3.jar

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

The agent uses classloader isolation and namespace shading:

1. A tiny 2-class shim (`AgentBootstrap`) loads on the system classloader — compiled to Java 8 bytecode
2. The embedded library JAR (`agent-impl.jar`) is injected onto the system classloader
3. All OTel SDK, ByteBuddy, and OkHttp classes are shaded under `io.last9.internal.*` — **safe even if your app bundles its own OTel SDK**
4. ByteBuddy class transformers intercept Vert.x APIs at load time
5. RxJava context propagation hooks are installed automatically
6. Log-trace correlation is auto-installed into Logback — no `logback.xml` changes needed

**No Maven dependency required.** The agent is fully self-contained. Your app doesn't need any `io.last9` dependency in `pom.xml`.

> **WARNING: Do NOT add `io.last9:vertx3-rxjava2-otel-autoconfigure` (or the v4 equivalent) as a Maven dependency when using the `-javaagent` approach.** The app's unshaded library classes will shadow the agent's shaded classes, causing a no-op tracer and zero spans. If you previously used library mode and are migrating to the agent, remove the `io.last9` dependencies from your `pom.xml`.

**Safe with existing OTel dependencies.** The agent's OTel SDK is fully isolated under `io.last9.internal.otel.*`. If your app already bundles `opentelemetry-api`, `opentelemetry-sdk`, or `opentelemetry-semconv` — no conflict. The agent ignores the app's OTel classes entirely.

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

1. **Check the agent loaded** — look for `bytecode instrumentation installed` in logs
2. **Check RxJava context propagation** — look for `RxJava context propagation installed`
3. **Check downstream reads `traceparent`** — the downstream service must be OTel-instrumented
4. **Verify `rxSend()` runs inside a handler** — trace context is only available within a Router handler chain

### No Spans Exported

1. Check `OTEL_EXPORTER_OTLP_ENDPOINT` is set and reachable
2. Check `OTEL_EXPORTER_OTLP_HEADERS` has correct auth
3. Look for `Connection refused` errors in stderr (OkHttp sender logs export failures)
4. Set `OTEL_LOG_LEVEL=debug` for verbose export logging

### Missing SERVER Spans (CLIENT spans present)

If CLIENT spans (database, HTTP client) flow but SERVER spans don't:

1. **Check transformation logs** — look for `transformed io.vertx.reactivex.ext.web.Router (loaded=false)` and `HttpServerAdviceHelper: wrapping requestHandler`
2. **Check the tracer status** — look for `Tracer OK (io.last9.internal.otel.sdk.trace.SdkTracer)` on stderr. If you see `Tracer is NO-OP`, the agent's SDK wasn't initialized correctly
3. **Check for classpath conflicts** — look for `WARNING: HttpServerAdviceHelper is missing version marker` on stderr. This means the app bundles an older `io.last9:vertx3-rxjava2-otel-autoconfigure` dependency that shadows the agent. **Fix: remove the `io.last9` dependency from your `pom.xml`**
4. **If `HttpServerAdviceHelper` log is missing** — the helper failed to load. Check for `failed to wrap requestHandler` WARN message on stderr with full diagnostics

## Why Not the OTel Java Agent?

The standard OpenTelemetry Java Agent assumes `ThreadLocal`-based context propagation, which breaks with Vert.x's event-loop model:

- Trace context lost after async HTTP client calls ([#11860](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/11860))
- Broken spans with virtual threads on Java 21 ([#10526](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/10526))
- RxJava operators lose trace context across thread hops

This library works *with* Vert.x's context model — using handler-based instrumentation (v3) or the native `VertxTracer` SPI (v4), with RxJava assembly hooks to propagate context across all operators.

## Pre-release / Beta Builds

Every push and PR builds JARs as GitHub Actions artifacts. Go to [Actions](https://github.com/last9/vertx-opentelemetry/actions/workflows/ci.yaml), click a run, and download the `jars-<sha>` artifact.

Tagged pre-releases appear on the [Releases](https://github.com/last9/vertx-opentelemetry/releases) page with downloadable JARs.

## Requirements

| Module | Java | Vert.x | RxJava |
|--------|------|--------|--------|
| `vertx3-otel-agent` | **8+** | 3.9+ | 2.x |
| `vertx4-otel-agent` | **11+** | 4.5+ | 3.x |
| `vertx3-rxjava2-otel-autoconfigure` (library) | 11+ | 3.9+ | 2.x |
| `vertx4-rxjava3-otel-autoconfigure` (library) | 11+ | 4.5+ | 3.x |

Both standalone agents work on **JDK and JRE** with full classloader isolation.

---

<details>
<summary><h2>Library Mode: Manual Traced* Wrappers</h2></summary>

> **Prefer the `-javaagent` approach.** Library mode is for environments where `-javaagent` is not feasible.

### Vert.x 4

```xml
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx4-rxjava3-otel-autoconfigure</artifactId>
    <version>2.2.3</version>
</dependency>
```

```java
// Use OtelLauncher as main class
// <mainClass>io.last9.tracing.otel.v4.OtelLauncher</mainClass>

// TracedRouter for route-pattern span names
import io.last9.tracing.otel.v4.TracedRouter;
Router router = TracedRouter.create(vertx);

// TracedDBPool for database spans
import io.last9.tracing.otel.v4.TracedDBPool;
TracedDBPool traced = TracedDBPool.wrap(PgPool.pool(vertx, opts, poolOpts), "postgresql", "mydb");

// Kafka batch consumer
import io.last9.tracing.otel.v4.KafkaTracing;
consumer.batchHandler(KafkaTracing.tracedBatchHandler(topicName, this::handleBatch));
```

### Vert.x 3

```xml
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx3-rxjava2-otel-autoconfigure</artifactId>
    <version>2.2.3</version>
</dependency>
```

```java
// OtelLauncher (alternative to -javaagent, requires JDK)
// <mainClass>io.last9.tracing.otel.v3.OtelLauncher</mainClass>

// Manual wrapper APIs
Router router = TracedRouter.create(vertx);
WebClient client = TracedWebClient.create(vertx);
SQLClient sql = TracedSQLClient.wrap(JDBCClient.createShared(vertx, config), "mysql", "mydb");
TracedAerospikeClient aero = TracedAerospikeClient.wrap(new AerospikeClient("localhost", 3000), "ns");
TracedKafkaProducer<String, String> producer = TracedKafkaProducer.wrap(KafkaProducer.create(vertx, config));
TracedKafkaConsumer.create(vertx, config, "topic", "group", records -> { ... });
```

### Manual SDK initialization

If using a custom main class (not OtelLauncher):

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
