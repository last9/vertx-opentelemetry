# Vert.x OpenTelemetry Auto-Configure

Drop-in OpenTelemetry instrumentation for Vert.x applications. Add the JAR, swap your main class, and get distributed tracing, log correlation, and RxJava context propagation — all configured via standard `OTEL_*` environment variables.

| Your Stack | Module |
|------------|--------|
| Vert.x 4.5+ / RxJava 3 | `vertx4-rxjava3-otel-autoconfigure` |
| Vert.x 3.9+ / RxJava 2 | `vertx3-rxjava2-otel-autoconfigure` |

## Quick Start

### 1. Install the JAR

Download from [GitHub Releases](https://github.com/last9/vertx-opentelemetry/releases) and install to your local Maven repository:

```bash
# For Vert.x 4:
mvn install:install-file -Dfile=vertx4-rxjava3-otel-autoconfigure-1.2.0.jar \
  -DgroupId=io.last9 -DartifactId=vertx4-rxjava3-otel-autoconfigure -Dversion=1.2.0 -Dpackaging=jar

# For Vert.x 3:
mvn install:install-file -Dfile=vertx3-rxjava2-otel-autoconfigure-1.2.0.jar \
  -DgroupId=io.last9 -DartifactId=vertx3-rxjava2-otel-autoconfigure -Dversion=1.2.0 -Dpackaging=jar
```

Then add to your `pom.xml`:

```xml
<!-- Vert.x 4 -->
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx4-rxjava3-otel-autoconfigure</artifactId>
    <version>1.2.0</version>
</dependency>

<!-- OR Vert.x 3 -->
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx3-rxjava2-otel-autoconfigure</artifactId>
    <version>1.2.0</version>
</dependency>
```

### 2. Use OtelLauncher as your main class

In your Maven shade/fat-jar plugin configuration:

```xml
<!-- Vert.x 4 -->
<mainClass>io.last9.tracing.otel.v4.OtelLauncher</mainClass>

<!-- Vert.x 3 -->
<mainClass>io.last9.tracing.otel.v3.OtelLauncher</mainClass>
```

<details>
<summary>Example: maven-shade-plugin configuration</summary>

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>io.last9.tracing.otel.v4.OtelLauncher</mainClass>
                    </transformer>
                    <!-- Required: merge OpenTelemetry SPI files -->
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                </transformers>
            </configuration>
        </execution>
    </executions>
</plugin>
```

</details>

### 3. Use TracedRouter instead of Router

Replace `Router.router(vertx)` with `TracedRouter.create(vertx)` in your verticle:

```java
// Before
Router router = Router.router(vertx);

// After — Vert.x 4
import io.last9.tracing.otel.v4.TracedRouter;
Router router = TracedRouter.create(vertx);

// After — Vert.x 3
import io.last9.tracing.otel.v3.TracedRouter;
Router router = TracedRouter.create(vertx);
```

This gives you:
- **Vert.x 4**: Route-pattern span names (`GET /v1/users/:id` instead of just `GET`)
- **Vert.x 3**: Full HTTP tracing with span creation, `traceparent` extraction, route-pattern span names, and request body buffering

> **Note**: For Vert.x 3, `TracedRouter` is required for HTTP tracing — there is no built-in tracing SPI.

> **Vert.x 3 — do not add `BodyHandler`**: `TracedRouter` buffers the request body itself before calling your handler, so `ctx.getBodyAsJson()` and `ctx.getBody()` work out of the box. Adding `BodyHandler.create()` will conflict with this mechanism.

### 4. Set environment variables and run

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=https://your-otlp-endpoint
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <token>"

java -jar app.jar run com.example.MainVerticle
```

You'll see in your application logs:

```
=== OpenTelemetry Auto-Configuration ===
Service: my-service
OTLP Endpoint: https://your-otlp-endpoint
OpenTelemetry SDK initialized successfully
W3C trace context propagation configured (traceparent header enabled)
Logback OpenTelemetry appender installed for log export
=== OpenTelemetry Ready ===
```

## What You Get

- **HTTP spans** for every incoming request, with method, path, status code
- **Route-pattern span names** like `GET /v1/users/:id` (not `GET /v1/users/42`)
- **Distributed tracing** via W3C `traceparent` header propagation
- **RxJava context propagation** — trace context flows across `subscribeOn`, `observeOn`, `flatMap`, and all operators
- **Kafka consumer tracing** (Vert.x 3 + 4) — `KafkaTracing.tracedBatchHandler()` creates a CONSUMER span per batch so `trace_id` appears in logs inside the handler
- **Database tracing** (Vert.x 3) — auto-instrumented wrappers for SQL (`TracedSQLClient`), Redis (`TracedRedisClient`), and Aerospike (`TracedAerospikeClient`), plus generic `DbTracing` for any other database
- **Auto-propagating WebClient** (Vert.x 3) — `TracedWebClient` auto-injects `traceparent` on every outgoing request — no per-call wrapping needed
- **Log-to-trace correlation** — every log line includes `trace_id` and `span_id`, so you can jump from a log line to its trace in your observability platform
- **Log export** — logs sent to your OTLP endpoint alongside traces, with trace context automatically attached

## Log-to-Trace Correlation

The library provides two levels of log-trace integration:

### 1. MDC injection (trace_id and span_id in every log line)

Add `MdcTraceTurboFilter` to your `logback.xml`. This injects `trace_id` and `span_id` into Logback's MDC before every log event, so you can search logs by trace ID or click through from a log line to its trace.

```xml
<configuration>
    <turboFilter class="io.last9.tracing.otel.MdcTraceTurboFilter"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} trace_id=%X{trace_id} span_id=%X{span_id} %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

Example log output:

```
14:23:01.456 [vert.x-eventloop-thread-0] INFO  c.e.UserHandler trace_id=4bf92f3577b34da6a3ce929d0e0e4736 span_id=00f067aa0ba902b7 Fetching user 42
```

### 2. OTLP log export (logs sent alongside traces)

Add the OpenTelemetry Logback appender to also export logs via OTLP. Exported logs automatically carry trace context, enabling log-to-trace correlation in backends like Grafana, Datadog, or Last9. No extra dependency needed — `OtelLauncher` calls `OpenTelemetryAppender.install()` automatically.

```xml
    <appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
        <captureExperimentalAttributes>true</captureExperimentalAttributes>
        <captureCodeAttributes>true</captureCodeAttributes>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="OTEL"/>
    </root>
```

> **Why MdcTraceTurboFilter?** Standard OpenTelemetry Logback MDC instrumentation relies on `ThreadLocal`, which doesn't work with Vert.x's event-loop context model. This TurboFilter bridges that gap by reading the current span directly from the OpenTelemetry context.

## Vert.x 3: Outgoing HTTP Tracing

Vert.x 3 has no tracing SPI for its HTTP client, so outgoing requests do not carry `traceparent`
automatically. Without it, downstream services create new root spans and the trace chain breaks.

### Option 1: TracedWebClient (recommended)

Use `TracedWebClient` as a drop-in replacement for `WebClient`. It auto-injects `traceparent` on
every outgoing request — no per-call wrapping needed:

```java
import io.last9.tracing.otel.v3.TracedWebClient;

// Instead of: WebClient client = WebClient.create(vertx);
WebClient client = TracedWebClient.create(vertx);

// traceparent is injected automatically:
client.getAbs(pricingServiceUrl + "/v1/price/" + symbol)
    .rxSend()
    .subscribe(...);
```

You can also wrap an existing `WebClient`, including custom subclasses:

```java
WebClient traced = TracedWebClient.wrap(existingClient);
```

#### Wrapping a custom WebClient subclass

If you have a custom `WebClient` subclass (e.g., one that adds auth headers or correlation IDs),
`wrap()` preserves your custom behavior. The tracing layer delegates to your client's overridden
methods and then injects `traceparent` on the result:

```java
// Your custom WebClient that adds auth headers
class AuthWebClient extends WebClient {
    @Override
    public HttpRequest<Buffer> get(int port, String host, String uri) {
        return super.get(port, host, uri)
                .putHeader("Authorization", "Bearer " + token);
    }
}

// Wrap it — auth headers AND traceparent are both injected
WebClient client = TracedWebClient.wrap(new AuthWebClient(vertx));
```

> **Note**: `TracedWebClient` is `final` and cannot be subclassed. Use `wrap()` to add tracing
> to your own `WebClient` instances.

### Option 2: Per-request injection

If you prefer fine-grained control, use `ClientTracing.inject()` on individual requests:

```java
import io.last9.tracing.otel.v3.ClientTracing;

ClientTracing.inject(webClient.getAbs(pricingServiceUrl + "/v1/price/" + symbol))
    .rxSend()
    .subscribe(...);
```

Both approaches require an active span (e.g., inside a `TracedRouter` handler). If called outside
an active span, no `traceparent` header is set.

Vert.x 4 handles outgoing HTTP propagation automatically for any client created from the traced `Vertx` instance.

## Troubleshooting: Disconnected Traces

If your outgoing calls show up as separate root traces instead of being connected to the incoming
request's trace, work through this checklist:

### 1. Verify the propagation chain

Three components must all be in place for distributed traces to work:

```
TracedRouter (creates SERVER span)
  → RxJava2ContextPropagation (carries context across thread hops)
    → TracedWebClient / ClientTracing.inject (writes traceparent header)
```

If any link is missing, the downstream service receives no `traceparent` and starts a new root trace.

### 2. Check `RxJava2ContextPropagation` is installed

This is the most common cause. OpenTelemetry stores the current span in a `ThreadLocal`. When RxJava
hops threads (via `subscribeOn`, `observeOn`, `flatMap` with async work), the `ThreadLocal` is empty
on the new thread — the outgoing call sees no active span and silently writes no header.

If you use `OtelLauncher` as your main class, this is handled automatically. If you have a custom
main class, you must call it yourself:

```java
// In your custom launcher or main method, BEFORE deploying verticles:
OtelSdkSetup.initialize();
RxJava2ContextPropagation.install();  // <-- don't forget this
```

### 3. Confirm you're using TracedWebClient or ClientTracing.inject

A plain `WebClient.create(vertx)` never injects trace headers. Verify your outgoing calls use one of:

```java
// Option A: TracedWebClient (automatic)
WebClient client = TracedWebClient.create(vertx);

// Option B: Per-request injection
ClientTracing.inject(webClient.getAbs(url)).rxSend();
```

### 4. Confirm outgoing calls happen inside a TracedRouter handler

The `traceparent` header is only written when there is an active span. `TracedRouter` opens a span
scope that covers your handler chain. If you make HTTP calls outside a handler (e.g., in a periodic
timer, EventBus consumer, or Kafka batch handler), there may be no active span.

For Kafka batch handlers, use `KafkaTracing.tracedBatchHandler()` to create a CONSUMER span first,
then make outgoing calls inside that handler.

### 5. Verify the downstream service reads `traceparent`

The downstream service must be instrumented with OpenTelemetry (or any W3C Trace Context compatible
library) and must extract the `traceparent` header from incoming requests. You can verify the header
is being sent by logging it:

```java
client.getAbs(url)
    .rxSend()
    .doOnSubscribe(d -> {
        // Check if traceparent was injected
        logger.info("trace_id={}", Span.current().getSpanContext().getTraceId());
    })
    .subscribe(...);
```

Or check the outgoing request headers in your observability platform's network view.

### 6. Check for context loss in RxJava chains

If you build a request object in one place and subscribe to it later (deferred pattern), the trace
context is captured at request-creation time, not at subscription time:

```java
// Context captured HERE (when get() is called):
HttpRequest<Buffer> req = tracedClient.get(8080, "host", "/api");

// NOT here (when rxSend subscribes):
req.rxSend().subscribe(...);
```

Make sure the request is created inside the handler where the span is active — not stored and reused
across different request contexts.

## Vert.x 3: Database Tracing

Vert.x 3 has no SPI for database clients, so MySQL, PostgreSQL, Redis, Aerospike, and other DB
calls produce no spans by default. Use the auto-instrumented wrapper clients for zero-code tracing,
or `DbTracing` for manual wrapping.

### Option 1: Auto-instrumented wrappers (recommended)

Swap your client creation line and every operation is traced automatically:

**SQL (MySQL / PostgreSQL):**

```java
import io.last9.tracing.otel.v3.TracedSQLClient;

// Instead of: SQLClient client = JDBCClient.createShared(vertx, config);
SQLClient client = TracedSQLClient.wrap(
        JDBCClient.createShared(vertx, config), "mysql", "orders_db");

// Every query automatically gets a CLIENT span — no manual wrapping:
client.rxQueryWithParams("SELECT * FROM orders WHERE id = ?", params)
    .subscribe(resultSet -> { ... });

// Connections are also auto-traced:
client.rxGetConnection()
    .flatMap(conn -> conn.rxQuery("SELECT 1").doFinally(conn::close))
    .subscribe(...);
```

**Redis:**

```java
import io.last9.tracing.otel.v3.TracedRedisClient;

// Instead of: RedisAPI redis = RedisAPI.api(connection);
RedisAPI redis = TracedRedisClient.wrap(RedisAPI.api(connection), "0");

// Common commands (GET, SET, HGETALL, DEL, LPUSH, etc.) are auto-traced:
redis.rxGet("session:abc").subscribe(response -> { ... });
redis.rxHgetall("user:42").subscribe(response -> { ... });
```

**Aerospike:**

```java
import io.last9.tracing.otel.v3.TracedAerospikeClient;

// Instead of: IAerospikeClient client = new AerospikeClient("localhost", 3000);
IAerospikeClient client = TracedAerospikeClient.wrap(
        new AerospikeClient("localhost", 3000), "my-namespace");

// Every data-plane call (get, put, delete, exists, operate, query, scanAll)
// automatically gets a CLIENT span:
Record record = client.get(null, new Key("my-namespace", "users", "user:123"));
client.put(null, key, new Bin("name", "Alice"));
```

### Option 2: Manual wrapping with DbTracing

For databases without an auto-instrumented wrapper, or for fine-grained control:

```java
import io.last9.tracing.otel.v3.DbTracing;

DbTracing db = DbTracing.create("mysql", "orders_db");

db.traceSingle("SELECT * FROM orders WHERE id = ?", () ->
        sqlClient.rxQueryWithParams(sql, params))
    .subscribe(resultSet -> { ... });

db.traceCompletable("DELETE FROM cache WHERE expired = true", () ->
        sqlClient.rxUpdate(sql).ignoreElement())
    .subscribe();
```

For synchronous clients:

```java
DbTracing aerospike = DbTracing.create("aerospike", "my-namespace");

Record result = aerospike.traceSync("GET user:123", () ->
        aerospikeClient.get(null, key));
```

Each span is named `{db.system} {operation}` (e.g., `mysql SELECT * FROM orders`) with attributes:
- `db.system` = the database identifier you provide
- `db.statement` = the operation description
- `db.name` = the database/namespace name

## Vert.x 4: Auto-Instrumented Components

Any Vert.x 4 client created from a traced `Vertx` instance is automatically instrumented:

| Component | Vert.x Module |
|-----------|--------------|
| HTTP Server/Client | `vertx-core` |
| EventBus | `vertx-core` |
| PostgreSQL | `vertx-pg-client` |
| MySQL | `vertx-mysql-client` |
| Redis | `vertx-redis-client` |
| Kafka producer / consumer poll | `vertx-kafka-client` |
| gRPC | `vertx-grpc` |

### Vert.x 4: Kafka batch consumer

The `VertxTracer` SPI does not instrument `KafkaConsumer.batchHandler()` callbacks, so
`trace_id` and `span_id` are empty in log lines produced inside a batch handler by default.
Use `KafkaTracing.tracedBatchHandler()` to wrap the handler with a CONSUMER span:

```java
import io.last9.tracing.otel.v4.KafkaTracing;

// In your verticle's start() method:
consumer.batchHandler(KafkaTracing.tracedBatchHandler(topicName, this::handleBatch));

private void handleBatch(KafkaConsumerRecords<String, String> records) {
    // Span.current() is now the CONSUMER span — trace_id appears in logs
    logger.info("Processing {} records", records.size());
    ...
}
```

The wrapper creates a span named `kafka.consume.batch` with kind `CONSUMER` and sets:
- `messaging.system` = `kafka`
- `messaging.destination.name` = the topic name you pass in
- `messaging.batch.message_count` = `records.size()`

Exceptions thrown by the handler are recorded on the span before being re-thrown, and the span is
always ended in a `finally` block.

### Vert.x 3: Kafka batch consumer

Vert.x 3 has no tracing SPI at all, so `trace_id` and `span_id` are empty in log lines produced
inside a `batchHandler()` callback by default. Use `KafkaTracing.tracedBatchHandler()` to wrap
the handler with a CONSUMER span:

```java
import io.last9.tracing.otel.v3.KafkaTracing;

// In your verticle's start() method:
consumer.batchHandler(KafkaTracing.tracedBatchHandler(topicName, this::handleBatch));

private void handleBatch(KafkaConsumerRecords<String, String> records) {
    // Span.current() is now the CONSUMER span — trace_id appears in logs
    logger.info("Processing {} records", records.size());
    ...
}
```

The wrapper creates a span named `kafka.consume.batch` with kind `CONSUMER` and sets the same
attributes as the Vert.x 4 variant:
- `messaging.system` = `kafka`
- `messaging.destination.name` = the topic name you pass in
- `messaging.batch.message_count` = `records.size()`

## Pre-release / Beta Builds

To test unreleased changes before a full release:

**Option 1: Download from CI** — every push and PR builds JARs as GitHub Actions artifacts.
Go to [Actions](https://github.com/last9/vertx-opentelemetry/actions/workflows/ci.yaml), click a
run, and download the `jars-<sha>` artifact.

**Option 2: Beta releases** — tagged pre-releases (e.g., `v1.3.0-beta.1`) appear on the
[Releases](https://github.com/last9/vertx-opentelemetry/releases) page marked as "Pre-release"
with downloadable JARs.

## Environment Variables

All standard [OpenTelemetry environment variables](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/) are supported. Key ones:

| Variable | Description | Default |
|----------|-------------|---------|
| `OTEL_SERVICE_NAME` | Service name in traces | `unknown-service` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP endpoint URL | `http://localhost:4318` |
| `OTEL_EXPORTER_OTLP_HEADERS` | Auth headers (URL-encoded) | - |
| `OTEL_RESOURCE_ATTRIBUTES` | Additional resource attributes | - |
| `OTEL_LOGS_EXPORTER` | Log exporter (`otlp` / `none`) | `otlp` |
| `OTEL_TRACES_SAMPLER` | Sampling strategy | `parentbased_always_on` |

## Why Not the OTel Java Agent?

The standard OpenTelemetry Java Agent assumes `ThreadLocal`-based context propagation, but Vert.x uses its own event-loop context model. This causes:

- Trace context lost after async HTTP client calls ([#11860](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/11860))
- Broken spans with virtual threads on Java 21 ([#10526](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/10526))
- RxJava operators lose trace context across thread hops

This library works with Vert.x's context model instead of fighting it — using the native `VertxTracer` SPI (v4) or handler-based instrumentation (v3), with RxJava assembly hooks to propagate context across all operators.

## Requirements

| Module | Java | Vert.x | RxJava |
|--------|------|--------|--------|
| `vertx4-rxjava3-otel-autoconfigure` | 11+ | 4.5+ | 3.x |
| `vertx3-rxjava2-otel-autoconfigure` | 11+ | 3.9+ | 2.x |

## License

MIT

## Contributing

Contributions welcome — please open an issue or submit a pull request.

## Support

- [GitHub Issues](https://github.com/last9/vertx-opentelemetry/issues)
- [Last9 Documentation](https://last9.io/docs)
