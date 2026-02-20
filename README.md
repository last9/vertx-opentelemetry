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
mvn install:install-file -Dfile=vertx4-rxjava3-otel-autoconfigure-1.0.0.jar \
  -DgroupId=io.last9 -DartifactId=vertx4-rxjava3-otel-autoconfigure -Dversion=1.0.0 -Dpackaging=jar

# For Vert.x 3:
mvn install:install-file -Dfile=vertx3-rxjava2-otel-autoconfigure-1.0.0.jar \
  -DgroupId=io.last9 -DartifactId=vertx3-rxjava2-otel-autoconfigure -Dversion=1.0.0 -Dpackaging=jar
```

Then add to your `pom.xml`:

```xml
<!-- Vert.x 4 -->
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx4-rxjava3-otel-autoconfigure</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- OR Vert.x 3 -->
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx3-rxjava2-otel-autoconfigure</artifactId>
    <version>1.0.0</version>
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

Use `ClientTracing.inject()` to propagate the current span's context into any `WebClient` request:

```java
import io.last9.tracing.otel.v3.ClientTracing;

// Wrap the request with ClientTracing.inject() before calling rxSend():
ClientTracing.inject(webClient.getAbs(pricingServiceUrl + "/v1/price/" + symbol))
    .rxSend()
    .subscribe(...);
```

Call `inject` inside a `TracedRouter` handler — the span is current at that point, so the
`traceparent` header will be set correctly. If called outside an active span, the call is a no-op.

Vert.x 4 handles outgoing HTTP propagation automatically for any client created from the traced `Vertx` instance.

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
| `vertx4-rxjava3-otel-autoconfigure` | 17+ | 4.5+ | 3.x |
| `vertx3-rxjava2-otel-autoconfigure` | 17+ | 3.9+ | 2.x |

## License

MIT

## Contributing

Contributions welcome — please open an issue or submit a pull request.

## Support

- [GitHub Issues](https://github.com/last9/vertx-opentelemetry/issues)
- [Last9 Documentation](https://last9.io/docs)
