# Vert.x OpenTelemetry Auto-Configure

Drop-in OpenTelemetry instrumentation for Vert.x applications. Add the JAR, swap your main class, and get distributed tracing, log correlation, and RxJava context propagation — all configured via standard `OTEL_*` environment variables.

| Your Stack | Module |
|------------|--------|
| Vert.x 4.5+ / RxJava 3 | `vertx4-rxjava3-otel-autoconfigure` |
| Vert.x 3.9+ / RxJava 2 | `vertx3-rxjava2-otel-autoconfigure` |

## Quick Start

### 1. Add the dependency

The library is published to [Maven Central](https://central.sonatype.com/search?q=io.last9). Add to your `pom.xml`:

```xml
<!-- Vert.x 4 -->
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx4-rxjava3-otel-autoconfigure</artifactId>
    <version>1.5.0</version>
</dependency>

<!-- OR Vert.x 3 -->
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx3-rxjava2-otel-autoconfigure</artifactId>
    <version>1.5.0</version>
</dependency>
```

> **Self-contained JAR**: Each module bundles all required OTel classes. You do **not** need a separate `vertx-otel-core` dependency.

### 2. Enable auto-instrumentation

Choose one of three options (Vert.x 3). Vert.x 4 users: skip to [Step 3](#3-start-tracing).

#### Option A: Standalone agent JAR (recommended — no code changes, works on JRE)

Download `vertx3-otel-agent-<version>.jar` from [Releases](https://github.com/last9/vertx-opentelemetry/releases) and run with `-javaagent`:

```bash
java -javaagent:vertx3-otel-agent-2.1.0.jar -jar app.jar
```

**No main class change, no manifest changes, works on JRE.** The agent uses classloader isolation (like the OTel Java agent) — only a tiny 2-class shim goes on the system classloader. All heavy dependencies (ByteBuddy, OTel SDK) are loaded in an isolated classloader from an embedded JAR.

The agent automatically:
1. Stores the `Instrumentation` handle via `OtelAgent.storeInstrumentation()` on the app classloader
2. Initializes the OTel SDK on the app classloader
3. Installs RxJava2 context propagation hooks
4. Installs ByteBuddy class transformers for Router, WebClient, Kafka, Aerospike, Redis, JDBC, and reactive SQL clients

If `OtelLauncher` is also used as main class, it detects that the agent already ran and becomes a no-op.

Your app must include `vertx3-rxjava2-otel-autoconfigure` as a Maven dependency (the agent's inlined advice resolves helper classes from your app's classpath).

#### Option B: OtelLauncher as main class (no JVM flags, requires JDK)

```xml
<!-- Vert.x 4 -->
<mainClass>io.last9.tracing.otel.v4.OtelLauncher</mainClass>

<!-- Vert.x 3 -->
<mainClass>io.last9.tracing.otel.v3.OtelLauncher</mainClass>
```

OtelLauncher self-attaches ByteBuddy before deploying verticles. Requires a **JDK** runtime (not JRE) for the Attach API. If both `-javaagent` and OtelLauncher are used together, OtelLauncher detects that `premain` already ran and skips self-attach.

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
                        <mainClass>io.last9.tracing.otel.v3.OtelLauncher</mainClass>
                    </transformer>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                </transformers>
            </configuration>
        </execution>
    </executions>
</plugin>
```

</details>

#### Option C: Manual wrappers (all versions, no bytecode instrumentation)

For environments where neither `-javaagent` nor OtelLauncher is feasible, use the `Traced*` wrapper APIs directly:

```java
import io.last9.tracing.otel.v3.TracedRouter;
Router router = TracedRouter.create(vertx);
```

See the [Outgoing HTTP Tracing](#vert.x-3-outgoing-http-tracing), [Database Tracing](#vertx-3-database-tracing), and [Kafka Tracing](#vertx-3-kafka-tracing) sections for details on each wrapper.

### 3. Start tracing

With auto-instrumentation enabled (Option A or B), **no code changes are needed**. ByteBuddy automatically instruments:

- **`Router.router(vertx)`** → SERVER spans with route-pattern names, `traceparent` extraction, body buffering
- **`WebClient.create(vertx)`** → CLIENT spans with `traceparent` injection on every outgoing request
- **`KafkaProducer.create(vertx, config)`** → PRODUCER spans with `traceparent` in Kafka headers
- **`KafkaConsumer` handlers** → CONSUMER spans per record with topic, partition, offset attributes

Your verticle uses plain Vert.x APIs — tracing is applied at the bytecode level:

```java
// No Traced* imports needed — these are auto-instrumented
Router router = Router.router(vertx);
WebClient client = WebClient.create(vertx);
KafkaProducer<String, String> producer = KafkaProducer.create(vertx, config);
```

> **Do not add `BodyHandler`**: The auto-instrumentation installs body buffering automatically. Adding `BodyHandler.create()` will cause "Request has already been read" errors.

**Vert.x 4**: Replace `Router.router(vertx)` with `TracedRouter.create(vertx)` for route-pattern span names. Vert.x 4's `VertxTracer` SPI handles HTTP server/client spans automatically; `TracedRouter` adds route-pattern span names (`GET /v1/users/:id` instead of just `GET`).

```java
import io.last9.tracing.otel.v4.TracedRouter;
Router router = TracedRouter.create(vertx);
```

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

- **Zero-code auto-instrumentation** (Vert.x 3, v2.1.0+) — Router, WebClient, Kafka Producer, and Kafka Consumer are instrumented via ByteBuddy bytecode transformation. No `Traced*` wrapper imports needed.
- **SERVER spans** for every incoming request, with method, path, status code
- **CLIENT spans** for every outgoing HTTP request (Vert.x 3), with `http.request.method`, `url.full`, `server.address`, `server.port`, `http.response.status_code`
- **Route-pattern span names** like `GET /v1/users/:id` (not `GET /v1/users/42`)
- **Distributed tracing** via W3C `traceparent` header propagation
- **RxJava context propagation** — trace context flows across `subscribeOn`, `observeOn`, `flatMap`, and all operators
- **Kafka producer + consumer tracing** (Vert.x 3 + 4) — `TracedKafkaProducer` creates PRODUCER spans with `traceparent` header propagation; `TracedKafkaConsumer` creates CONSUMER spans per batch with one-line setup
- **Database tracing** (Vert.x 3) — auto-instrumented wrappers for reactive MySQL (`TracedMySQLClient`), legacy SQL (`TracedSQLClient`), Redis (`TracedRedisClient`), and Aerospike (`TracedAerospikeClient`), plus generic `DbTracing` for any other database
- **Database tracing** (Vert.x 4) — `TracedDBPool` wraps any reactive SQL pool (PostgreSQL, MySQL) with CLIENT spans including the SQL statement; `DbTracing` for wrapping arbitrary operations with custom span names
- **Generic RxJava2 client wrapping** (Vert.x 3) — `TracedRxClient.wrap()` adds CLIENT spans to any RxJava2 interface via dynamic proxy — works with any third-party MySQL/Aerospike/custom data-access client
- **Auto-tracing WebClient** (Vert.x 3) — `TracedWebClient` creates CLIENT spans and injects `traceparent` on every outgoing request — no per-call wrapping needed
- **Worker thread context propagation** (Vert.x 3) — `TracedVertx.rxExecuteBlocking()` carries OTel context from event loop to worker threads so blocking calls produce connected spans
- **Log-to-trace correlation** — every log line includes `trace_id` and `span_id`, so you can jump from a log line to its trace in your observability platform
- **Log export** — logs sent to your OTLP endpoint alongside traces, with trace context automatically attached
- **Process / host resource attributes** — `process.pid`, `process.runtime.name`, `process.runtime.version`, `host.name`, `os.type`, `os.description` attached to every span automatically (equivalent to the OTel Java agent)
- **JVM metrics** — `jvm.memory.used`, `jvm.gc.duration`, `jvm.thread.count`, `jvm.cpu.time` and more, exported when `OTEL_METRICS_EXPORTER=otlp` is set
- **Exception events on spans** — when a handler calls `ctx.fail(throwable)`, the exception is recorded as a span event with `exception.type`, `exception.message`, and `exception.stacktrace`

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

Vert.x 3 has no tracing SPI for its HTTP client, so outgoing requests produce no spans and do not
carry `traceparent` automatically. Without it, downstream services create new root spans and the
trace chain breaks.

### Option 1: TracedWebClient (recommended)

Use `TracedWebClient` as a drop-in replacement for `WebClient`. It creates a CLIENT span per
OTel HTTP semantic conventions and injects `traceparent` on every outgoing request:

```java
import io.last9.tracing.otel.v3.TracedWebClient;

// Instead of: WebClient client = WebClient.create(vertx);
WebClient client = TracedWebClient.create(vertx);

// CLIENT span + traceparent injection happen automatically on rxSend():
client.getAbs(pricingServiceUrl + "/v1/price/" + symbol)
    .rxSend()
    .subscribe(...);
```

Each outgoing request produces a CLIENT span with these attributes:
- `http.request.method` — the HTTP method (GET, POST, etc.)
- `url.full` — the full request URL
- `server.address` — the target host
- `server.port` — the target port
- `http.response.status_code` — the response status code

You can also wrap an existing `WebClient`, including custom subclasses:

```java
WebClient traced = TracedWebClient.wrap(existingClient);
```

#### Wrapping a custom WebClient subclass

If you have a custom `WebClient` subclass (e.g., one that adds auth headers or correlation IDs),
`wrap()` preserves your custom behavior. The tracing layer delegates to your client's overridden
methods and then creates a CLIENT span with `traceparent` injection on `rxSend()`:

```java
// Your custom WebClient that adds auth headers
class AuthWebClient extends WebClient {
    @Override
    public HttpRequest<Buffer> get(int port, String host, String uri) {
        return super.get(port, host, uri)
                .putHeader("Authorization", "Bearer " + token);
    }
}

// Wrap it — auth headers AND traceparent are both present, CLIENT span is created
WebClient client = TracedWebClient.wrap(new AuthWebClient(vertx));
```

> **Note**: `TracedWebClient` is `final` and cannot be subclassed. Use `wrap()` to add tracing
> to your own `WebClient` instances.

### Option 2: Per-request tracing with ClientTracing

For fine-grained control, use `ClientTracing.traced()` on individual requests. This creates a
CLIENT span with full OTel semantic conventions:

```java
import io.last9.tracing.otel.v3.ClientTracing;

// Recommended: creates CLIENT span + injects traceparent
ClientTracing.traced(webClient.getAbs(pricingServiceUrl + "/v1/price/" + symbol))
    .rxSend()
    .subscribe(...);
```

For lightweight header-only injection (no CLIENT span), use `ClientTracing.inject()`:

```java
// Only injects traceparent header, no CLIENT span created
ClientTracing.inject(webClient.getAbs(url))
    .rxSend()
    .subscribe(...);
```

Both approaches require an active span (e.g., inside a `TracedRouter` handler). If called outside
an active span, no `traceparent` header is set.

Vert.x 4 handles outgoing HTTP propagation automatically for any client created from the traced `Vertx` instance.

## Troubleshooting

### Disconnected Traces

If your outgoing calls show up as separate root traces instead of being connected to the incoming
request's trace, work through this checklist:

### 1. Verify the propagation chain

Three components must all be in place for distributed traces to work:

**With zero-code auto-instrumentation (v2.1.0+):**
```
Standalone agent (-javaagent:vertx3-otel-agent.jar) or OtelLauncher (self-attaches ByteBuddy)
  → Router.router(vertx) auto-instrumented (creates SERVER span)
  → RxJava2ContextPropagation (carries context across thread hops)
    → WebClient.create(vertx) auto-instrumented (creates CLIENT span + writes traceparent)
    → KafkaProducer.create() auto-instrumented (creates PRODUCER span + writes traceparent)
    → KafkaConsumer.handler() auto-instrumented (creates CONSUMER span per record)
```

**With manual wrappers:**
```
TracedRouter (creates SERVER span)
  → RxJava2ContextPropagation (carries context across thread hops)
    → TracedWebClient / ClientTracing.traced (creates CLIENT span + writes traceparent)
    → TracedKafkaProducer (creates PRODUCER span + writes traceparent into headers)
    → TracedVertx.rxExecuteBlocking → TracedAerospikeClient / TracedRxClient (CLIENT spans on worker threads)
```

If any link is missing, the downstream service receives no `traceparent` and starts a new root trace.

### 2. Check `RxJava2ContextPropagation` is installed

This is the most common cause. OpenTelemetry stores the current span in a `ThreadLocal`. When RxJava
hops threads (via `subscribeOn`, `observeOn`, `flatMap` with async work), the `ThreadLocal` is empty
on the new thread — the outgoing call sees no active span and silently writes no header.

If you use `OtelLauncher` as your main class, this is handled automatically. If you have a custom
main class, you must call it yourself:

```java
import io.last9.tracing.otel.OtelSdkSetup;
import io.last9.tracing.otel.v3.RxJava2ContextPropagation;

// In your custom launcher or main method, BEFORE deploying verticles:
OtelSdkSetup.initialize();
RxJava2ContextPropagation.install();  // <-- don't forget this
```

### 3. Confirm WebClient tracing is active

With **zero-code instrumentation** (v2.1.0+), `WebClient.create(vertx)` is auto-instrumented — CLIENT spans and `traceparent` injection happen automatically. No manual wrapping needed.

With **manual wrappers**, verify your outgoing calls use one of:

```java
// Option A: TracedWebClient (automatic — CLIENT span + traceparent)
WebClient client = TracedWebClient.create(vertx);

// Option B: Per-request tracing (CLIENT span + traceparent)
ClientTracing.traced(webClient.getAbs(url)).rxSend();

// Option C: Header-only injection (no CLIENT span)
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

The CLIENT span and `traceparent` injection happen when `rxSend()` subscribes, so the active span
at subscription time determines the parent. If you build a request object in one handler and
subscribe in another context, the trace may be disconnected:

```java
// Request object created here, but no span work yet:
HttpRequest<Buffer> req = tracedClient.get(8080, "host", "/api");

// CLIENT span + traceparent captured HERE (when rxSend subscribes):
req.rxSend().subscribe(...);
```

Make sure `rxSend()` is called inside the handler where the parent span is active — not deferred
to a different request context.

## Vert.x 3: Database Tracing

Vert.x 3 has no SPI for database clients, so MySQL, PostgreSQL, Redis, Aerospike, and other DB
calls produce no spans by default. Use the auto-instrumented wrapper clients for zero-code tracing,
or `DbTracing` for manual wrapping.

### Option 1: Auto-instrumented wrappers (recommended)

Swap your client creation line and every operation is traced automatically:

**SQL (legacy `SQLClient` — MySQL / PostgreSQL):**

```java
import io.last9.tracing.otel.v3.TracedSQLClient;

// Instead of: SQLClient client = JDBCClient.createShared(vertx, config);
SQLClient client = TracedSQLClient.wrap(
        JDBCClient.createShared(vertx, config), "mysql", "orders_db");

// db name is optional — omit if not known:
SQLClient client = TracedSQLClient.wrap(JDBCClient.createShared(vertx, config), "mysql");

// Every query automatically gets a CLIENT span — no manual wrapping:
client.rxQueryWithParams("SELECT * FROM orders WHERE id = ?", params)
    .subscribe(resultSet -> { ... });

// Connections obtained via rxGetConnection() are also fully auto-traced.
// All operations on the connection produce CLIENT spans: query, update,
// call (stored procedures), execute (DDL), batch, COMMIT, and ROLLBACK.
// Lifecycle operations (setAutoCommit, close) do not produce spans.
client.rxGetConnection()
    .flatMap(conn -> conn.rxQuery("SELECT 1")
        .doFinally(conn::close))
    .subscribe(...);
```

**MySQL (reactive client — `vertx-mysql-client`):**

```java
import io.last9.tracing.otel.v3.TracedMySQLClient;
import io.vertx.reactivex.mysqlclient.MySQLPool;
import io.vertx.reactivex.sqlclient.Tuple;

// Instead of: MySQLPool pool = MySQLPool.pool(vertx, connectOptions, poolOptions);
TracedMySQLClient mysql = TracedMySQLClient.wrap(
        MySQLPool.pool(vertx, connectOptions, poolOptions), "orders_db");

// db name is optional:
TracedMySQLClient mysql = TracedMySQLClient.wrap(
        MySQLPool.pool(vertx, connectOptions, poolOptions));

// Every query automatically gets a CLIENT span:
mysql.query("SELECT * FROM orders")
    .subscribe(rows -> { ... });

// Parameterised prepared query:
mysql.preparedQuery("SELECT * FROM orders WHERE id = ?", Tuple.of(orderId))
    .subscribe(rows -> { ... });

// Use unwrap() for pool-level operations not covered above (transactions, etc.):
mysql.unwrap().withTransaction(conn -> ...);
```

> **Note**: `TracedMySQLClient` wraps the newer reactive `MySQLPool` API (`vertx-mysql-client`).
> For the legacy async MySQL client (`vertx-mysql-postgresql-client`) that implements
> `io.vertx.ext.sql.SQLClient`, use `TracedSQLClient` instead (see below).

**Redis:**

```java
import io.last9.tracing.otel.v3.TracedRedisClient;

// Instead of: RedisAPI redis = RedisAPI.api(connection);
RedisAPI redis = TracedRedisClient.wrap(RedisAPI.api(connection), "0");

// db namespace is optional:
RedisAPI redis = TracedRedisClient.wrap(RedisAPI.api(connection));

// Common commands (GET, SET, HGETALL, DEL, LPUSH, etc.) are auto-traced:
redis.rxGet("session:abc").subscribe(response -> { ... });
redis.rxHgetall("user:42").subscribe(response -> { ... });
```

**Aerospike:**

```java
import io.last9.tracing.otel.v3.TracedAerospikeClient;

// Instead of: AerospikeClient client = new AerospikeClient("localhost", 3000);
TracedAerospikeClient client = TracedAerospikeClient.wrap(
        new AerospikeClient("localhost", 3000), "my-namespace");

// namespace is optional:
TracedAerospikeClient client = TracedAerospikeClient.wrap(new AerospikeClient("localhost", 3000));

// Every data-plane call (get, put, delete, exists, operate, query, scanAll)
// automatically gets a CLIENT span — same method signatures as AerospikeClient:
Record record = client.get(null, new Key("my-namespace", "users", "user:123"));
client.put(null, key, new Bin("name", "Alice"));
client.delete(null, key);

// For admin/lifecycle/async operations not covered above, use unwrap():
client.unwrap().registerUdf(...);
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

## Vert.x 3: Generic RxJava2 Client Wrapping

For third-party clients that the library has no compile-time dependency on (e.g., custom
MySQL/Aerospike clients, or any RxJava2 data-access layer), use `TracedRxClient.wrap()`.
It creates a dynamic proxy that intercepts methods returning `Single`, `Completable`, or `Maybe`
and wraps them with CLIENT spans automatically. Non-reactive methods pass through untouched.

```java
import io.last9.tracing.otel.v3.TracedRxClient;

// Wrap a MySQL client:
MysqlClient traced = TracedRxClient.wrap(
        mysqlClient, MysqlClient.class, "mysql", "orders_db");

// db name is optional:
MysqlClient traced = TracedRxClient.wrap(mysqlClient, MysqlClient.class, "mysql");

// Wrap an Aerospike client:
AerospikeClient traced = TracedRxClient.wrap(
        aerospikeClient, AerospikeClient.class, "aerospike", "my-namespace");

// All RxJava2 method calls now produce CLIENT spans automatically:
traced.rxQuery("SELECT * FROM users")
    .subscribe(result -> ...);
```

Each CLIENT span is named `{dbSystem} {methodName}` (e.g., `mysql rxQuery`) with attributes:
- `db.system` = the database identifier you provide
- `db.name` = the database/namespace name
- `db.statement` = the method name (or custom operation name)

### Custom span naming

By default, the span operation name is the method name. Provide an `OperationNameFn` to include
more context (e.g., the SQL statement):

```java
TracedRxClient.wrap(client, MysqlClient.class, "mysql", "orders_db",
        (method, args) -> method.getName() + " " + args[0]);
// Span name: "mysql rxQuery SELECT * FROM users"
```

## Vert.x 3: Span Name Updater

`TracedRouter` already sets route-pattern span names (e.g., `GET /v1/users/:id`) automatically.
`SpanNameUpdater` is available for cases where you manage spans manually and want the same
behaviour — for example, if you create SERVER spans yourself without using `TracedRouter`.

### Per-route: updateSpanName handler

Add `SpanNameUpdater::updateSpanName` as the first handler on a route. It reads the matched route
pattern from the routing context, updates the current span's name, and sets the `http.route`
attribute before calling `ctx.next()`:

```java
import io.last9.tracing.otel.v3.SpanNameUpdater;

router.get("/v1/users/:id")
    .handler(SpanNameUpdater::updateSpanName)   // updates span name to "GET /v1/users/:id"
    .handler(myHandler);
```

### Router-wide: addToAllRoutes

`addToAllRoutes()` installs two global handlers on the router:

1. A high-priority (`order -1000`) handler that captures the active span and attaches a
   `headersEndHandler` — fired when the response is sent — that updates the span name with the
   matched route pattern and sets `http.route` and `http.response.status_code`.
2. A low-priority handler that captures the matched route path for the `headersEndHandler` to use.

```java
import io.last9.tracing.otel.v3.SpanNameUpdater;

Router router = Router.router(vertx);
SpanNameUpdater.addToAllRoutes(router);  // call before defining routes

router.get("/v1/orders/:orderId").handler(ctx -> {
    // Span name will be updated to "GET /v1/orders/:orderId" when the response is sent
    ctx.response().end(payload);
});
```

5xx responses also set the span status to `ERROR` via `addToAllRoutes`.

## Vert.x 3: Worker Thread Context Propagation

Vert.x's `rxExecuteBlocking()` dispatches work to a worker thread pool. Since OTel context is
thread-local, the worker thread has no access to the active span from the event loop — traced
clients produce disconnected root traces instead of parenting under the current request.

`TracedVertx.rxExecuteBlocking()` captures the OTel context on the event loop and restores it
on the worker thread:

```java
import io.last9.tracing.otel.v3.TracedVertx;

// Before (manual boilerplate):
Context otelCtx = Context.current();
vertx.<Record>rxExecuteBlocking(promise -> {
    try (Scope ignored = otelCtx.makeCurrent()) {
        Record r = aerospikeClient.get(null, key);
        promise.complete(r);
    }
});

// After:
TracedVertx.<Record>rxExecuteBlocking(vertx, promise -> {
    Record r = aerospikeClient.get(null, key);
    promise.complete(r);
});
```

Any traced client called inside the handler (e.g., `TracedAerospikeClient`, `DbTracing`) will
now parent under the event loop's active span.

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

The wrapper creates a span named `{topic} process` (per OTel convention) with kind `CONSUMER` and sets:
- `messaging.system` = `kafka`
- `messaging.destination.name` = the topic name you pass in
- `messaging.operation` = `process`
- `messaging.batch.message_count` = `records.size()`

Exceptions thrown by the handler are recorded on the span before being re-thrown, and the span is
always ended in a `finally` block.

### Vert.x 3: Kafka Tracing

Vert.x 3 has no tracing SPI, so Kafka operations produce no spans by default.

#### Producer: TracedKafkaProducer

Wrap your `KafkaProducer` once and every send creates a PRODUCER span with `traceparent` injected
into Kafka headers:

```java
import io.last9.tracing.otel.v3.TracedKafkaProducer;

// Instead of: KafkaProducer<String, String> producer = KafkaProducer.create(vertx, config);
TracedKafkaProducer<String, String> producer = TracedKafkaProducer.wrap(
        KafkaProducer.create(vertx, config));

// Every send automatically gets a PRODUCER span + context propagation:
KafkaProducerRecord<String, String> record =
        KafkaProducerRecord.create("orders", "order-123", payload);
producer.rxSend(record)
    .subscribe(metadata -> logger.info("Sent to partition {}", metadata.getPartition()));
```

Each PRODUCER span includes:
- `messaging.system` = `kafka`
- `messaging.destination.name` = topic
- `messaging.operation` = `publish`
- `messaging.kafka.message.key` = record key
- `messaging.kafka.destination.partition` = partition (set after send)
- `messaging.kafka.message.offset` = offset (set after send)

For per-call control without the wrapper, use `KafkaTracing.tracedSend(producer, record)` directly.

#### Consumer: TracedKafkaConsumer (recommended)

`TracedKafkaConsumer.create()` handles the full consumer setup in one call — creates the consumer,
sets the traced batch handler, starts polling, and subscribes to the topic:

```java
import io.last9.tracing.otel.v3.TracedKafkaConsumer;

Map<String, String> config = new HashMap<>();
config.put("bootstrap.servers", "localhost:9092");
config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
config.put("group.id", "my-consumer-group");
config.put("auto.offset.reset", "earliest");

TracedKafkaConsumer.create(vertx, config, "orders", "my-consumer-group", records -> {
    // Span.current() is the CONSUMER span — trace_id appears in logs
    logger.info("Processing {} records", records.size());
    for (int i = 0; i < records.size(); i++) {
        logger.info("Record: {}", records.recordAt(i).value());
    }
});
```

Each batch produces a CONSUMER span named `{topic} process` (per OTel convention) with attributes:
- `messaging.system` = `kafka`
- `messaging.destination.name` = topic
- `messaging.operation` = `process`
- `messaging.batch.message_count` = batch size
- `messaging.kafka.consumer.group` = consumer group (if provided)

**Context isolation and SpanLinks**: Per OTel messaging semantic conventions, CONSUMER spans are
**root spans** — they do not inherit any HTTP SERVER span that may be active on the Vert.x event
loop thread. If the incoming Kafka records carry a `traceparent` header (injected by
`TracedKafkaProducer`), the consumer span adds a **SpanLink** pointing to the producer span instead
of a parent/child relationship. This correctly models the async, decoupled nature of Kafka — the
producer and consumer appear in separate but linked traces.

Any traced client called inside the handler (e.g., `TracedAerospikeClient`, `TracedWebClient`,
`DbTracing`) automatically parents under the CONSUMER span.

#### Consumer: KafkaTracing.setupConsumer (existing consumer)

If you already have a `KafkaConsumer` instance (e.g., you need custom partition assignment or
offset control), `KafkaTracing.setupConsumer()` wires all four required steps in one call:

```java
import io.last9.tracing.otel.v3.KafkaTracing;

KafkaConsumer<String, String> consumer = KafkaConsumer.create(vertx, config);

// Wires batchHandler, exceptionHandler, no-op handler, and subscribe — all in one call
KafkaTracing.setupConsumer(consumer, "orders", "my-consumer-group", records -> {
    logger.info("Processing {} records", records.size());
});
```

This is equivalent to the following manual wiring:

```java
consumer.getDelegate().batchHandler(KafkaTracing.tracedBatchHandler(
        topicName, "my-consumer-group", this::handleBatch, GlobalOpenTelemetry.get()));
consumer.exceptionHandler(KafkaTracing.tracedExceptionHandler(topicName, GlobalOpenTelemetry.get()));
consumer.handler(record -> {});  // required to start polling
consumer.subscribe(topicName);
```

## Vert.x 4: Database Tracing

The `VertxTracer` SPI automatically traces HTTP client/server spans, but database clients
(PostgreSQL, MySQL, etc.) do not produce spans automatically. Use `TracedPgPool` or `DbTracing`
to add CLIENT spans with SQL-statement-level granularity.

### TracedDBPool (recommended)

Wraps any reactive SQL `Pool` — including `PgPool` and `MySQLPool` — and adds a CLIENT span to
every `query()` and `preparedQuery()` call:

```java
import io.last9.tracing.otel.v4.TracedDBPool;
import io.vertx.rxjava3.pgclient.PgPool;

// PostgreSQL:
PgPool pool = PgPool.pool(vertx, connectOptions, poolOptions);
TracedDBPool traced = TracedDBPool.wrap(pool, "postgresql", "orders_db");

// MySQL:
MySQLPool mysqlPool = MySQLPool.pool(vertx, connectOptions, poolOptions);
TracedDBPool tracedMysql = TracedDBPool.wrap(mysqlPool, "mysql", "orders_db");

// db name is optional — omit if not relevant:
TracedDBPool traced = TracedDBPool.wrap(pool, "postgresql");

// Every query automatically gets a CLIENT span:
traced.query("SELECT * FROM orders")
    .subscribe(rows -> { ... });

// Parameterised query:
traced.preparedQuery("SELECT * FROM orders WHERE id = $1", Tuple.of(42))
    .subscribe(rows -> { ... });

// Use unwrap() for pool-level operations not covered above (transactions, etc.):
traced.unwrap().withTransaction(conn -> ...);
```

Each CLIENT span includes:
- `db.system` = the system identifier you passed (e.g. `"postgresql"`, `"mysql"`)
- `db.statement` = the SQL string
- `db.name` = the database name you passed to `wrap()`

### DbTracing (manual / custom clients)

For databases without a dedicated wrapper, or for fine-grained control over any RxJava 3 operation:

```java
import io.last9.tracing.otel.v4.DbTracing;

DbTracing db = DbTracing.create("postgresql", "orders_db");

db.traceSingle("SELECT * FROM orders WHERE id = $1", () ->
        pool.preparedQuery("SELECT * FROM orders WHERE id = $1")
            .rxExecute(Tuple.of(42)))
    .subscribe(rows -> { ... });

db.traceCompletable("DELETE FROM cache WHERE expired = true", () ->
        pool.query("DELETE FROM cache WHERE expired = true")
            .rxExecute().ignoreElement())
    .subscribe();
```

---

## Pre-release / Beta Builds

To test unreleased changes before a full release:

**Option 1: Download from CI** — every push and PR builds JARs as GitHub Actions artifacts.
Go to [Actions](https://github.com/last9/vertx-opentelemetry/actions/workflows/ci.yaml), click a
run, and download the `jars-<sha>` artifact. Install locally with:

```bash
mvn install:install-file -Dfile=vertx4-rxjava3-otel-autoconfigure-<version>.jar \
  -DgroupId=io.last9 -DartifactId=vertx4-rxjava3-otel-autoconfigure -Dversion=<version> -Dpackaging=jar
```

**Option 2: Beta releases** — tagged pre-releases appear on the
[Releases](https://github.com/last9/vertx-opentelemetry/releases) page marked as "Pre-release"
with downloadable JARs. Use the latest pre-release if you need unreleased features ahead of
the next stable version.

## Environment Variables

All standard [OpenTelemetry environment variables](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/) are supported. Key ones:

| Variable | Description | Default |
|----------|-------------|---------|
| `OTEL_SERVICE_NAME` | Service name in traces | `unknown-service` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP endpoint URL | `http://localhost:4318` |
| `OTEL_EXPORTER_OTLP_HEADERS` | Auth headers (URL-encoded) | - |
| `OTEL_EXPORTER_OTLP_TIMEOUT` | HTTP client timeout per export (ms) | `10000` |
| `OTEL_RESOURCE_ATTRIBUTES` | Additional resource attributes | - |
| `OTEL_LOGS_EXPORTER` | Log exporter (`otlp` / `none`) | `otlp` |
| `OTEL_METRICS_EXPORTER` | Metrics exporter (`otlp` / `none`) | `otlp` |
| `OTEL_TRACES_SAMPLER` | Sampling strategy | `parentbased_always_on` |
| `OTEL_METRIC_EXPORT_INTERVAL` | Metrics push interval (ms) | `60000` |
| `OTEL_BSP_SCHEDULE_DELAY` | Span batch export interval (ms) | `5000` |
| `OTEL_BSP_MAX_EXPORT_BATCH_SIZE` | Max spans per export request | `512` |

> **Tip:** When exporting to a remote OTLP backend, set `OTEL_EXPORTER_OTLP_TIMEOUT=30000`
> to avoid timeout errors on the first metrics export (which contains all JVM metric streams).

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

> **Zero-code instrumentation** (Vert.x 3, v2.1.0+): The standalone agent (`-javaagent:vertx3-otel-agent.jar`) works on both **JDK and JRE** with full classloader isolation. The `OtelLauncher` self-attach approach requires a **JDK** (Attach API). If self-attach fails on a JRE, the application falls back to manual `Traced*` wrapper mode with a warning logged.

## License

MIT

## Contributing

Contributions welcome — please open an issue or submit a pull request.

## Support

- [GitHub Issues](https://github.com/last9/vertx-opentelemetry/issues)
- [Last9 Documentation](https://last9.io/docs)
