# SPEC: Vert.x Internal Metrics via OpenTelemetry (v2.0.0)

**Status**: Draft
**Author**: Prathamesh Sonpatki
**Date**: 2026-02-26

---

## 1. Goal

Add automatic Vert.x internal metrics (event loop, worker pool, HTTP server, EventBus) to the `vertx4-rxjava3-otel-autoconfigure` fat JAR. Metrics flow through the same OTel SDK instance already configured for traces and logs — same `OTEL_*` env vars, same OTLP endpoint, zero additional user configuration.

**Out of scope for this release**: Vert.x 3 metrics (future iteration).

---

## 2. What users get (zero config)

After upgrading to 2.0.0, the following metrics are emitted automatically via `OtelLauncher`:

### Event Loop
| Metric | Type | Description |
|--------|------|-------------|
| `vertx.eventloop.pending.tasks` | Gauge | Tasks pending execution on each event loop thread |
| `vertx.eventloop.lag` | Gauge (ms) | **Custom probe**: time between task submission and execution. Measures actual event loop blocking. |

> **Note**: `vertx-micrometer-metrics` exposes `vertx.eventloop.pending.tasks` natively. True lag (submission-to-execution delay) requires a custom `setPeriodic`-based probe — see §5.3.

### Worker Pool
| Metric | Type | Description |
|--------|------|-------------|
| `vertx.pool.queue.pending` | Gauge | Tasks queued waiting for a worker thread |
| `vertx.pool.queue.time` | Timer | Time tasks spend waiting in the queue |
| `vertx.pool.usage` | Timer | Time worker threads are in use |
| `vertx.pool.in.use` | Gauge | Worker threads currently executing |
| `vertx.pool.ratio` | Gauge | Pool usage ratio (in-use / max) |

### HTTP Server
| Metric | Type | Description |
|--------|------|-------------|
| `vertx.http.server.active.connections` | Gauge | Open TCP connections |
| `vertx.http.server.active.requests` | Gauge | Requests currently being processed |
| `vertx.http.server.requests` | Counter | Total requests received |
| `vertx.http.server.response.time` | Timer | End-to-end response latency histogram |
| `vertx.http.server.bytes.read` | DistributionSummary | Request body bytes |
| `vertx.http.server.bytes.written` | DistributionSummary | Response body bytes |

### EventBus
| Metric | Type | Description |
|--------|------|-------------|
| `vertx.eventbus.sent` | Counter | Messages sent point-to-point |
| `vertx.eventbus.received` | Counter | Messages received |
| `vertx.eventbus.published` | Counter | Messages published (broadcast) |
| `vertx.eventbus.delivered` | Counter | Messages delivered to handlers |
| `vertx.eventbus.pending` | Gauge | Messages in-flight (sent but not yet handled) |
| `vertx.eventbus.reply.failures` | Counter | Reply timeout/no-handlers failures |

All metrics include a `vertx_address` or `vertx_pool_name` tag where applicable.

---

## 3. Design

### 3.1 Architecture

```
OtelLauncher.beforeStartingVertx()
  │
  ├── OtelSdkSetup.initialize()          (existing — returns OpenTelemetry)
  │
  ├── Build MeterRegistry                (NEW)
  │     └── OpenTelemetryMeterRegistry   ← OTel Micrometer shim
  │           bridges into existing SDK
  │
  ├── options.setTracingOptions(...)      (existing)
  │
  ├── options.setMetricsOptions(          (NEW)
  │     MicrometerMetricsOptions
  │       .setMicrometerRegistry(registry)
  │       .setEnabled(true)
  │       .setJvmMetricsEnabled(false)    ← JVM metrics already done by runtime-telemetry-java8
  │   )
  │
  └── EventLoopLagProbe.install(vertx)    (NEW — post-deploy hook)
```

### 3.2 Micrometer → OTel bridge

Use `opentelemetry-micrometer-1.5` shim from `io.opentelemetry.instrumentation`:

```java
MeterRegistry registry = OpenTelemetryMeterRegistry
    .builder(openTelemetry)
    .build();
```

This bridges every Micrometer `Meter` into the OTel `MeterProvider` already set up by `OtelSdkSetup`. No separate exporter, no separate endpoint — all metrics flow through the same `OTEL_EXPORTER_OTLP_ENDPOINT`.

### 3.3 Event loop lag probe

`vertx-micrometer-metrics` does not measure actual lag (submission-to-execution delay). We implement a dedicated probe:

```java
// EventLoopLagProbe.java
// Runs a periodic task on every event loop thread.
// Measures: actual execution time - scheduled time = lag.
vertx.setPeriodic(PROBE_INTERVAL_MS, id -> {
    long expected = lastScheduled + PROBE_INTERVAL_MS;
    long lag = System.currentTimeMillis() - expected;
    lagGauge.set(Math.max(0, lag));
    lastScheduled = System.currentTimeMillis();
});
```

Installed after `Vertx` is created via `OtelLauncher.afterStartingVertx(Vertx vertx)`.

---

## 4. New dependencies

All bundled (shaded) into the fat JAR — no user-facing transitive dependencies added.

| Artifact | Version | Purpose |
|----------|---------|---------|
| `io.vertx:vertx-micrometer-metrics` | `4.5.x` (match existing vertx4.version) | Vert.x metrics SPI + Micrometer bridges |
| `io.micrometer:micrometer-core` | managed by vertx-micrometer-metrics | Micrometer meter types |
| `io.opentelemetry.instrumentation:opentelemetry-micrometer-1.5` | `${opentelemetry-instrumentation.version}-alpha` | Bridges Micrometer → OTel SDK |

`vertx-micrometer-metrics` depends on `micrometer-core` which Vert.x already manages in its BOM. The `opentelemetry-micrometer-1.5` shim is already in the instrumentation BOM used by `vertx-otel-core`.

### Shade relocations

Add relocations for Micrometer classes to avoid conflicts with users who also use Micrometer directly:

```xml
<relocation>
    <pattern>io.micrometer</pattern>
    <shadedPattern>io.last9.shaded.micrometer</shadedPattern>
</relocation>
```

---

## 5. Files to create / modify

### 5.1 `vertx4-rxjava3-otel-autoconfigure/pom.xml`

Add to `<dependencies>`:

```xml
<!-- Vert.x Micrometer metrics integration (bundled/shaded) -->
<dependency>
    <groupId>io.vertx</groupId>
    <artifactId>vertx-micrometer-metrics</artifactId>
    <!-- version managed by vertx4 BOM -->
    <scope>compile</scope>
</dependency>

<!-- OTel Micrometer shim: bridges Micrometer meters into OTel SDK -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-micrometer-1.5</artifactId>
    <version>${opentelemetry-instrumentation.version}-alpha</version>
</dependency>
```

Add Micrometer relocation to the shade plugin config.

### 5.2 `vertx4-rxjava3-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v4/OtelLauncher.java`

Modify `beforeStartingVertx` to add metrics setup:

```java
@Override
public void beforeStartingVertx(VertxOptions options) {
    OpenTelemetry openTelemetry = OtelSdkSetup.initialize();
    options.setTracingOptions(new OpenTelemetryOptions(openTelemetry));
    RxJava3ContextPropagation.install();

    // NEW: Vert.x internal metrics via Micrometer → OTel bridge
    MeterRegistry registry = OpenTelemetryMeterRegistry
        .builder(openTelemetry)
        .build();
    options.setMetricsOptions(new MicrometerMetricsOptions()
        .setMicrometerRegistry(registry)
        .setJvmMetricsEnabled(false) // Already provided by runtime-telemetry-java8
        .setEnabled(true));
}

// NEW: install event loop lag probe after Vertx is created
@Override
public void afterStartingVertx(Vertx vertx) {
    EventLoopLagProbe.install(vertx, /* meterRegistry from above */ );
}
```

**Problem**: `beforeStartingVertx` and `afterStartingVertx` don't share state directly — the `MeterRegistry` needs to be passed between them. Solution: store it as an instance field on `OtelLauncher`.

### 5.3 NEW: `vertx4-rxjava3-otel-autoconfigure/src/main/java/io/last9/tracing/otel/v4/EventLoopLagProbe.java`

Custom event loop lag measurement probe:

```java
public class EventLoopLagProbe {
    private static final long PROBE_INTERVAL_MS = 100; // 100ms probe frequency

    public static void install(Vertx vertx, MeterRegistry registry) {
        AtomicLong lagMs = new AtomicLong(0);
        // Register as a Micrometer gauge (bridges to OTel via shim)
        Gauge.builder("vertx.eventloop.lag", lagMs, AtomicLong::get)
            .description("Event loop lag in milliseconds")
            .baseUnit("ms")
            .register(registry);

        // Schedule periodic probe on Vert.x event loop
        long[] lastRun = {System.currentTimeMillis()};
        vertx.setPeriodic(PROBE_INTERVAL_MS, id -> {
            long now = System.currentTimeMillis();
            long lag = now - (lastRun[0] + PROBE_INTERVAL_MS);
            lagMs.set(Math.max(0, lag));
            lastRun[0] = now;
        });
    }
}
```

### 5.4 Parent `pom.xml`

Bump version: `1.5.0` → `2.0.0`.

---

## 6. Version: 2.0.0

This is a **major version bump** because:
- Fat JAR now includes shaded Micrometer classes — if a user already has Micrometer on their classpath, the shaded relocation prevents conflicts, but the JAR size increases significantly (~1MB)
- `MicrometerMetricsOptions` sets a global `VertxOptions` — if users were setting their own `MetricsOptions`, this overrides it

### Backwards compatibility

- Tracing and log correlation behaviour unchanged
- `TracedRouter` API unchanged
- `OtelLauncher` main class names unchanged
- All `OTEL_*` env vars still work
- **Breaking**: if a user was setting custom `MetricsOptions` via their own `Launcher` subclass overriding `beforeStartingVertx`, they must now merge with our `MicrometerMetricsOptions`

---

## 7. Implementation sequence

1. Read current `OtelLauncher.java`, `OtelSdkSetup.java`, `vertx4-rxjava3-otel-autoconfigure/pom.xml`, parent `pom.xml`
2. Add new Maven dependencies to v4 module pom and parent BOM
3. Add Micrometer shade relocation to shade plugin config
4. Implement `EventLoopLagProbe.java`
5. Modify `OtelLauncher.java` — add `MeterRegistry` field, wire `beforeStartingVertx` + `afterStartingVertx`
6. Run `mvn test` — verify existing tests pass
7. Add integration test: verify Micrometer gauge appears as OTel metric in `InMemoryMetricExporter`
8. Bump version to `2.0.0` in parent pom
9. Update `README.md` — add metrics section documenting what's emitted and how to disable (`OTEL_METRICS_EXPORTER=none`)

---

## 8. Open questions

1. **Probe interval**: 100ms for event loop lag probe — is this too frequent? Too infrequent? Should it be configurable via an env var?
2. **Metric naming**: Should Vert.x metric names be prefixed (`last9.vertx.*`) or kept as-is (`vertx.*`)? Keeping `vertx.*` is consistent with Vert.x docs and dashboards.
3. **Disabling metrics**: If a user sets `OTEL_METRICS_EXPORTER=none`, the OTel SDK drops all metrics — this is the correct "disable" path. Should we also support a `OTEL_VERTX_METRICS_ENABLED=false` flag to skip `MicrometerMetricsOptions` setup entirely?
4. **Fat JAR size**: Current JAR is ~15MB. Adding Micrometer + OTel shim (shaded) will add ~1.5MB. Acceptable?
