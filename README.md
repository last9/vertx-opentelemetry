# Vert.x OpenTelemetry Auto-Configure

Zero-code OpenTelemetry auto-instrumentation for Vert.x applications. Supports both Vert.x 4 + RxJava 3 and Vert.x 3 + RxJava 2.

## Why This Library?

The standard OpenTelemetry Java Agent has [known issues](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues?q=vertx) with Vert.x:

| Issue | Impact |
|-------|--------|
| [#11860](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/11860) | Context lost after async HTTP client/gRPC calls |
| [#10526](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/10526) | Virtual threads break spans in Java 21 |
| RxJava instrumentation | Context propagation only - no span creation |

**Root cause**: The agent assumes ThreadLocal-based context, but Vert.x uses its own Context model.

This library integrates with Vert.x's native tracing SPI (v4) or provides handler-based instrumentation (v3), properly handling context across async boundaries and RxJava operators.

## Choose Your Module

| Your Stack | Module | Maven Artifact |
|------------|--------|---------------|
| Vert.x 4.5+ / RxJava 3 | `vertx4-rxjava3-otel-autoconfigure` | `io.last9:vertx4-rxjava3-otel-autoconfigure:1.0.0` |
| Vert.x 3.9+ / RxJava 2 | `vertx3-rxjava2-otel-autoconfigure` | `io.last9:vertx3-rxjava2-otel-autoconfigure:1.0.0` |

Both modules share the same API surface (`OtelLauncher`, `TracedRouter`, `RxJavaContextPropagation`) with version-appropriate implementations.

## Features

- **Zero code changes** - just add the dependency and use `OtelLauncher`
- **Distributed tracing** via W3C `traceparent` header
- **RxJava context propagation** across all operators and schedulers
- **Log correlation** with `trace_id` and `span_id` in MDC
- **Log export** via OTLP (automatic Logback appender integration)
- **Standard OTEL_* environment variables** for configuration

## Quick Start

### 1. Add Dependency

Download the JAR from [GitHub Releases](https://github.com/last9/vertx-opentelemetry/releases) and install it to your local Maven repository:

**Vert.x 4 + RxJava 3:**

```bash
mvn install:install-file \
  -Dfile=vertx4-rxjava3-otel-autoconfigure-1.0.0.jar \
  -DgroupId=io.last9 \
  -DartifactId=vertx4-rxjava3-otel-autoconfigure \
  -Dversion=1.0.0 \
  -Dpackaging=jar
```

```xml
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx4-rxjava3-otel-autoconfigure</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Vert.x 3 + RxJava 2:**

```bash
mvn install:install-file \
  -Dfile=vertx3-rxjava2-otel-autoconfigure-1.0.0.jar \
  -DgroupId=io.last9 \
  -DartifactId=vertx3-rxjava2-otel-autoconfigure \
  -Dversion=1.0.0 \
  -Dpackaging=jar
```

```xml
<dependency>
    <groupId>io.last9</groupId>
    <artifactId>vertx3-rxjava2-otel-autoconfigure</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Set Main Class

Configure your fat JAR to use `OtelLauncher`:

**Vert.x 4:**

```xml
<mainClass>io.last9.tracing.otel.v4.OtelLauncher</mainClass>
```

**Vert.x 3:**

```xml
<mainClass>io.last9.tracing.otel.v3.OtelLauncher</mainClass>
```

### 3. Configure Environment Variables

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=https://your-otlp-endpoint
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <token>"
```

### 4. Run Your Application

```bash
java -jar app.jar run com.example.MainVerticle
```

That's it! All HTTP requests and logs are now traced automatically.

## Better Span Names with TracedRouter

Use `TracedRouter` for automatic route-pattern span names:

**Vert.x 4:**

```java
import io.last9.tracing.otel.v4.TracedRouter;

Router router = TracedRouter.create(vertx);
```

**Vert.x 3:**

```java
import io.last9.tracing.otel.v3.TracedRouter;

Router router = TracedRouter.create(vertx);

// Or, with an explicit OpenTelemetry instance:
Router router = TracedRouter.create(vertx, openTelemetry);
```

This changes span names from `GET` to `GET /v1/users/:id` — using the route pattern, not the actual path.

## Auto-Instrumented Components

### Vert.x 4

Any Vert.x 4 client created from a traced `Vertx` instance is **automatically instrumented** via the `vertx-opentelemetry` module:

| Component | Vert.x Module | Auto-Instrumented |
|-----------|--------------|-------------------|
| HTTP Server | `vertx-core` | Yes |
| HTTP Client | `vertx-core` | Yes |
| EventBus | `vertx-core` | Yes |
| PostgreSQL | `vertx-pg-client` | Yes |
| MySQL | `vertx-mysql-client` | Yes |
| Redis | `vertx-redis-client` | Yes |
| Kafka | `vertx-kafka-client` | Yes |
| gRPC | `vertx-grpc` | Yes |

### Vert.x 3

Vert.x 3 does not have a built-in tracing SPI, so instrumentation is handler-based via `TracedRouter`:

| Component | How | Notes |
|-----------|-----|-------|
| HTTP Server (incoming) | `TracedRouter.create(vertx)` | Automatic span creation |
| HTTP Client (outgoing) | Manual header injection | See [Distributed Tracing](#distributed-tracing) |
| RxJava operators | `RxJava2ContextPropagation.install()` | Automatic (done by OtelLauncher) |

## Log Correlation

Add the MDC TurboFilter to your `logback.xml` to inject `trace_id` and `span_id` into every log line:

```xml
<configuration>
    <turboFilter class="io.last9.tracing.otel.MdcTraceTurboFilter"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - trace_id=%X{trace_id} span_id=%X{span_id} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

To export logs via OTLP, add the OpenTelemetry appender:

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

`OtelLauncher` calls `OpenTelemetryAppender.install(openTelemetry)` automatically.

## Distributed Tracing

### Vert.x 4

Automatic W3C `traceparent` propagation for both incoming and outgoing HTTP requests. Works with any Vert.x HttpClient created from a traced Vertx instance.

### Vert.x 3

**Incoming**: Automatic — `TracedRouter` extracts `traceparent` from incoming requests and creates child spans.

**Outgoing**: Manual injection required (Vert.x 3 has no tracing SPI for HTTP client):

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;

// In your handler, inject traceparent into outgoing requests:
GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
    .inject(Context.current(), httpClientRequest,
            (req, key, value) -> req.putHeader(key, value));
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `OTEL_SERVICE_NAME` | Service name in traces | `unknown-service` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP endpoint URL | `http://localhost:4318` |
| `OTEL_EXPORTER_OTLP_HEADERS` | Auth headers (URL-encoded) | - |
| `OTEL_RESOURCE_ATTRIBUTES` | Additional resource attributes | - |
| `OTEL_LOGS_EXPORTER` | Log exporter (`otlp`/`none`) | `otlp` |
| `OTEL_TRACES_SAMPLER` | Sampling strategy | `parentbased_always_on` |

## Architecture

```
vertx-otel-autoconfigure (parent)
├── vertx-otel-core              # Shared: OtelSdkSetup, MdcTraceTurboFilter
├── vertx4-rxjava3-otel-autoconfigure  # Vert.x 4 + RxJava 3 integration
└── vertx3-rxjava2-otel-autoconfigure  # Vert.x 3 + RxJava 2 integration
```

### Components

| Class | Package (v4) | Package (v3) | Purpose |
|-------|-------------|-------------|---------|
| `OtelLauncher` | `io.last9.tracing.otel.v4` | `io.last9.tracing.otel.v3` | Main entry point |
| `TracedRouter` | `io.last9.tracing.otel.v4` | `io.last9.tracing.otel.v3` | Router with tracing |
| `RxJava3ContextPropagation` | `io.last9.tracing.otel.v4` | - | RxJava 3 context hooks |
| `RxJava2ContextPropagation` | - | `io.last9.tracing.otel.v3` | RxJava 2 context hooks |
| `SpanNameUpdater` | `io.last9.tracing.otel.v4` | `io.last9.tracing.otel.v3` | Route-pattern span names |
| `MdcTraceTurboFilter` | `io.last9.tracing.otel` | `io.last9.tracing.otel` | MDC trace injection |
| `OtelSdkSetup` | `io.last9.tracing.otel` | `io.last9.tracing.otel` | SDK auto-configuration |

## Requirements

| Module | Java | Vert.x | RxJava |
|--------|------|--------|--------|
| `vertx4-rxjava3-otel-autoconfigure` | 17+ | 4.5+ | 3.x |
| `vertx3-rxjava2-otel-autoconfigure` | 17+ | 3.9+ | 2.x |

## License

MIT

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.

## Support

- [GitHub Issues](https://github.com/last9/vertx-opentelemetry/issues)
- [Last9 Documentation](https://docs.last9.io)
