# Vert.x RxJava3 OpenTelemetry Auto-Configure

Zero-code OpenTelemetry auto-instrumentation for Vert.x + RxJava3 applications.

## Why This Library?

The standard OpenTelemetry Java Agent has [known issues](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues?q=vertx) with Vert.x:

| Issue | Impact |
|-------|--------|
| [#11860](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/11860) | Context lost after async HTTP client/gRPC calls |
| [#10526](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/10526) | Virtual threads break spans in Java 21 |
| RxJava instrumentation | Context propagation only - no span creation |

**Root cause**: The agent assumes ThreadLocal-based context, but Vert.x uses its own Context model.

This library uses Vert.x's native OpenTelemetry integration (`vertx-opentelemetry`) which properly handles context storage via `VertxContextStorage`.

## Features

- ✅ **Zero code changes** - just add the dependency and use `OtelLauncher`
- ✅ **Distributed tracing** via W3C `traceparent` header (incoming + outgoing)
- ✅ **RxJava3 context propagation** across all operators and schedulers
- ✅ **Log correlation** with `trace_id` and `span_id` in MDC
- ✅ **Log export** via OTLP (automatic Logback appender integration)
- ✅ **Standard OTEL_* environment variables** for configuration

### Auto-Instrumented Vert.x Clients

Any Vert.x client created from a traced `Vertx` instance is **automatically instrumented** — no additional code or dependencies required:

| Component | Vert.x Module | Auto-Instrumented |
|-----------|--------------|-------------------|
| HTTP Server | `vertx-core` | ✅ Yes |
| HTTP Client | `vertx-core` | ✅ Yes |
| EventBus | `vertx-core` | ✅ Yes |
| PostgreSQL | `vertx-pg-client` | ✅ Yes |
| MySQL | `vertx-mysql-client` | ✅ Yes |
| Redis | `vertx-redis-client` | ✅ Yes |
| Kafka Producer/Consumer | `vertx-kafka-client` | ✅ Yes |
| gRPC Server/Client | `vertx-grpc` | ✅ Yes |
| MongoDB | `vertx-mongo-client` | ✅ Yes |
| AMQP | `vertx-amqp-client` | ✅ Yes |

> **How it works:** `vertx-opentelemetry` hooks into the Vert.x tracing SPI. When you set `OpenTelemetryOptions` on the `Vertx` instance (done automatically by `OtelLauncher`), every Vert.x client created from that instance gets tracing for free.

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>io.vertx.tracing</groupId>
    <artifactId>vertx-rxjava3-otel-autoconfigure</artifactId>
    <version>0.9.0</version>
</dependency>
```

### 2. Set Main Class

Configure your fat JAR to use `OtelLauncher`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <configuration>
        <transformers>
            <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                <mainClass>io.vertx.tracing.otel.OtelLauncher</mainClass>
            </transformer>
        </transformers>
    </configuration>
</plugin>
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

That's it! All HTTP requests, database queries, and logs are now traced automatically.

## What Gets Traced

| Component | Spans Created | Key Attributes |
|-----------|---------------|----------------|
| HTTP Server | `GET /v1/users/:id` | `http.method`, `http.route`, `http.status_code` |
| HTTP Client | `HTTP GET api.example.com` | `http.url`, `http.method`, `http.status_code` |
| PostgreSQL / MySQL | `SELECT users...` | `db.system`, `db.statement`, `db.name` |
| Redis | `GET`, `SET`, `HGETALL` | `db.system=redis`, `db.statement` |
| Kafka | `topic send`, `topic receive` | `messaging.system=kafka`, `messaging.destination` |
| gRPC | `package.Service/Method` | `rpc.system=grpc`, `rpc.method`, `rpc.status_code` |
| EventBus | `eventbus.send address` | `messaging.destination` |

## Better Span Names

By default, Vert.x creates spans with just the HTTP method (`GET`, `POST`). Use `TracedRouter` for automatic route-pattern span names:

```java
import io.vertx.tracing.otel.TracedRouter;

// Instead of: Router router = Router.router(vertx);
Router router = TracedRouter.create(vertx);
```

This changes span names from `GET` to `GET /v1/users/:id` — using the route pattern, not the actual path.

## Log Correlation

Two features work together to give you full log observability:

### 1. Console logs with trace context (MDC)

Add the MDC TurboFilter to your `logback.xml` to inject `trace_id` and `span_id` into every log line:

```xml
<configuration>
    <turboFilter class="io.vertx.tracing.otel.MdcTraceTurboFilter"/>

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

Your logs will now include trace context:

```
10:30:45.123 [vert.x-eventloop-0] INFO  UserService - trace_id=abc123def456 span_id=789xyz - Fetching user: user123
```

### 2. Log export via OTLP (log attributes)

To export logs to your observability backend (with `trace_id`, `span_id`, and `severity` as structured log attributes), add the OpenTelemetry appender:

```xml
<configuration>
    <turboFilter class="io.vertx.tracing.otel.MdcTraceTurboFilter"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - trace_id=%X{trace_id} span_id=%X{span_id} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
        <captureExperimentalAttributes>true</captureExperimentalAttributes>
        <captureCodeAttributes>true</captureCodeAttributes>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="OTEL"/>
    </root>
</configuration>
```

`OtelLauncher` calls `OpenTelemetryAppender.install(openTelemetry)` automatically, so the appender connects to your configured OTLP endpoint. Logs appear in your backend with `trace_id` and `span_id` as structured attributes, enabling direct correlation with traces.

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `OTEL_SERVICE_NAME` | Service name in traces | `unknown-service` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP endpoint URL | `http://localhost:4318` |
| `OTEL_EXPORTER_OTLP_HEADERS` | Auth headers (URL-encoded) | - |
| `OTEL_RESOURCE_ATTRIBUTES` | Additional resource attributes | - |
| `OTEL_LOGS_EXPORTER` | Log exporter (`otlp`/`none`) | `otlp` |
| `OTEL_TRACES_SAMPLER` | Sampling strategy | `parentbased_always_on` |

## Distributed Tracing

The library automatically propagates W3C `traceparent` headers for distributed tracing:

- **Incoming**: Extracts `traceparent` from incoming HTTP requests, creating child spans under the parent trace
- **Outgoing**: Injects `traceparent` into outgoing HTTP client requests, propagating the trace to downstream services

This works automatically when using a Vert.x `HttpClient` created from a traced `Vertx` instance. No code changes needed.

## How It Works

1. **OtelLauncher** extends Vert.x's `Launcher` and configures OpenTelemetry in `beforeStartingVertx()`
2. **OpenTelemetry SDK** is auto-configured from `OTEL_*` environment variables
3. **W3C trace context propagation** is verified and auto-healed if fat-jar shading broke SPI discovery
4. **Vert.x tracing** is enabled via `OpenTelemetryOptions`
5. **RxJava3 hooks** are installed to propagate context across operators
6. **Logback appender** is installed for OTLP log export
7. **Your verticle** starts with tracing already configured

## Components

| Class | Purpose |
|-------|---------|
| `OtelLauncher` | Main entry point - configures OTel before Vert.x starts |
| `RxJava3ContextPropagation` | Propagates trace context across RxJava operators |
| `TracedRouter` | Drop-in Router factory with automatic route-pattern span names |
| `SpanNameUpdater` | Updates span names to include HTTP route (used by TracedRouter) |
| `MdcTraceTurboFilter` | Injects trace context into Logback MDC |

## Comparison: Before vs After

### Before (Manual instrumentation)

```java
// Every handler needs tracing code
public void getUser(RoutingContext ctx) {
    Span span = tracer.spanBuilder("getUser").startSpan();
    try (Scope scope = span.makeCurrent()) {
        MDC.put("trace_id", span.getSpanContext().getTraceId());
        // ... business logic
    } finally {
        span.end();
    }
}
```

### After (Zero-code with this library)

```java
// Just business logic - tracing is automatic
public void getUser(RoutingContext ctx) {
    log.info("Fetching user: {}", userId);  // trace_id auto-injected
    // ... business logic
}
```

## Requirements

- Java 17+
- Vert.x 4.5+
- RxJava 3.x

## License

MIT

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.

## Support

- [GitHub Issues](https://github.com/last9/vertx-rxjava3-otel-autoconfigure/issues)
- [Last9 Documentation](https://docs.last9.io)
