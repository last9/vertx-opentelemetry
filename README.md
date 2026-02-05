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

- ✅ **Zero code changes** - just add the dependency
- ✅ **Automatic HTTP server/client spans** via Vert.x OpenTelemetry
- ✅ **Automatic database spans** for PostgreSQL, MySQL, etc.
- ✅ **Automatic EventBus tracing**
- ✅ **RxJava3 context propagation** across all operators
- ✅ **Log correlation** with `trace_id` and `span_id`
- ✅ **Standard OTEL_* environment variables** for configuration

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>io.vertx.tracing</groupId>
    <artifactId>vertx-rxjava3-otel-autoconfigure</artifactId>
    <version>1.0.0</version>
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

| Component | Spans Created | Attributes |
|-----------|---------------|------------|
| HTTP Server | `GET /v1/users` | `http.method`, `http.route`, `http.status_code` |
| HTTP Client | `HTTP GET api.example.com` | `http.url`, `http.method` |
| PostgreSQL | `SELECT users...` | `db.system`, `db.statement`, `db.name` |
| EventBus | `eventbus.send address` | `messaging.destination` |

## Better Span Names

By default, Vert.x creates spans with just the HTTP method (`GET`, `POST`). Use `SpanNameUpdater` for better names:

```java
import io.vertx.tracing.otel.SpanNameUpdater;

// In your MainVerticle, after defining routes:
SpanNameUpdater.addToAllRoutes(router);
```

This changes span names from `GET` to `GET /v1/users`.

## Log Correlation

Add the MDC TurboFilter to your `logback.xml`:

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

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `OTEL_SERVICE_NAME` | Service name in traces | `unknown-service` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP endpoint URL | `http://localhost:4318` |
| `OTEL_EXPORTER_OTLP_HEADERS` | Auth headers (URL-encoded) | - |
| `OTEL_RESOURCE_ATTRIBUTES` | Additional resource attributes | - |
| `OTEL_LOGS_EXPORTER` | Log exporter (`otlp`/`none`) | `otlp` |
| `OTEL_TRACES_SAMPLER` | Sampling strategy | `parentbased_always_on` |

## How It Works

1. **OtelLauncher** extends Vert.x's `Launcher` and configures OpenTelemetry in `beforeStartingVertx()`
2. **OpenTelemetry SDK** is auto-configured from `OTEL_*` environment variables
3. **Vert.x tracing** is enabled via `OpenTelemetryOptions`
4. **RxJava3 hooks** are installed to propagate context across operators
5. **Your verticle** starts with tracing already configured

## Components

| Class | Purpose |
|-------|---------|
| `OtelLauncher` | Main entry point - configures OTel before Vert.x starts |
| `RxJava3ContextPropagation` | Propagates trace context across RxJava operators |
| `SpanNameUpdater` | Updates span names to include HTTP route |
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
