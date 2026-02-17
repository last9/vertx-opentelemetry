# CLAUDE.md

## Project Overview

Multi-module Maven project providing zero-code OpenTelemetry auto-instrumentation for Vert.x applications. Two version-specific modules share a common core.

- **Last9 homepage**: https://last9.io
- **Last9 docs**: https://last9.io/docs
- **GitHub repo**: https://github.com/last9/vertx-opentelemetry

## Architecture

```
vertx-otel-autoconfigure (parent POM)
├── vertx-otel-core                        # Shared: OtelSdkSetup, MdcTraceTurboFilter
├── vertx4-rxjava3-otel-autoconfigure      # Vert.x 4 + RxJava 3 (uses VertxTracer SPI)
└── vertx3-rxjava2-otel-autoconfigure      # Vert.x 3 + RxJava 2 (handler-based, no SPI)
```

**Key difference**: Vert.x 4 has a `VertxTracer` SPI so spans are created automatically. Vert.x 3 does NOT have this SPI, so `TracedRouter` creates SERVER spans manually via handler-based instrumentation.

## Build & Test

```bash
# Build all modules
mvn clean install

# Build a specific module (use -am to include dependencies)
mvn clean install -pl vertx-otel-core -am
mvn clean test -pl vertx3-rxjava2-otel-autoconfigure -am

# Run all tests
mvn test
```

Java 17+ is required. No special environment setup needed for tests (in-memory OTel exporters are used).

## Module Details

| Module | Vert.x | RxJava | Package |
|--------|--------|--------|---------|
| `vertx-otel-core` | - | - | `io.last9.tracing.otel` |
| `vertx4-rxjava3-otel-autoconfigure` | 4.5.10 | 3.x | `io.last9.tracing.otel.v4` |
| `vertx3-rxjava2-otel-autoconfigure` | 3.9.16 | 2.x | `io.last9.tracing.otel.v3` |

## Code Conventions

- **Package naming**: `io.last9.tracing.otel.v4` for Vert.x 4, `io.last9.tracing.otel.v3` for Vert.x 3, `io.last9.tracing.otel` for shared core
- **Vert.x 3 uses British spelling**: `normalisedPath()` not `normalizedPath()`
- **Vert.x 3 codegen types**: `io.vertx.reactivex.*` (v3) vs `io.vertx.rxjava3.*` (v4)
- **RxJava types**: `io.reactivex.*` (v2) vs `io.reactivex.rxjava3.*` (v3)
- **Fat JAR packaging**: Each version module uses `maven-shade-plugin` to produce standalone JARs
- **OpenTelemetry semantic conventions**: Use `io.opentelemetry.semconv.HttpAttributes` and `io.opentelemetry.semconv.ServerAttributes`

## Testing Patterns

- **TestOtelSetup**: Each module has a test helper that creates an in-memory OTel SDK with `InMemorySpanExporter`
- **VertxTestContext**: Tests use `@ExtendWith(VertxExtension.class)` and `VertxTestContext` for async assertions
- **RxJava plugin reset**: Tests must call `RxJavaPlugins.reset()` in `@BeforeEach` and reset the `installed` AtomicBoolean flag via reflection
- **Span waiting**: Use polling loop (`waitForSpans`) since spans are exported asynchronously
- **Port 0**: Always use `.rxListen(0)` and `server.actualPort()` to avoid port conflicts
- **AssertJ**: Use AssertJ (`assertThat`) for all assertions, not JUnit assertions

## CI/CD

- GitHub Actions workflow at `.github/workflows/release.yaml`
- Releases triggered by version tags (`v*`)
- Produces two fat JARs as release assets

## Dependencies (managed in parent POM)

- OpenTelemetry SDK: 1.35.0
- OpenTelemetry Semconv: 1.30.1-alpha
- Logback OpenTelemetry Appender: 2.1.0-alpha
- JUnit 5 + AssertJ for testing
