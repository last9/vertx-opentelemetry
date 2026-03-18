# CLAUDE.md

## Project Overview

Multi-module Maven project providing zero-code OpenTelemetry auto-instrumentation for Vert.x applications. Two version-specific modules share a common core.

- **Last9 homepage**: https://last9.io
- **Last9 docs**: https://last9.io/docs
- **GitHub repo**: https://github.com/last9/vertx-opentelemetry

## Architecture

```
vertx-otel-autoconfigure (parent POM)
├── vertx-otel-core                        # Shared: OtelSdkSetup, MdcTraceTurboFilter, OtelAgent
├── vertx4-rxjava3-otel-autoconfigure      # Vert.x 4 + RxJava 3 (uses VertxTracer SPI)
├── vertx3-rxjava2-otel-autoconfigure      # Vert.x 3 + RxJava 2 (handler-based + ByteBuddy)
└── vertx3-otel-agent                      # Standalone -javaagent with classloader isolation
```

**Key difference**: Vert.x 4 has a `VertxTracer` SPI so spans are created automatically. Vert.x 3 does NOT have this SPI, so `TracedRouter` creates SERVER spans manually via handler-based instrumentation.

### Agent Classloader Architecture

The agent JAR embeds `vertx3-rxjava2-otel-autoconfigure` as `inst/agent-impl.jar` and injects it onto the system classloader at startup via `Instrumentation.appendToSystemClassLoaderSearch()`. ByteBuddy advice is **inlined** into target methods on the app classloader.

## Build & Test

```bash
# Build all modules (Java 17+ required)
mvn package -B --no-transfer-progress -DskipTests

# Run all tests
mvn test -B --no-transfer-progress

# Build a specific module (use -am to include dependencies)
mvn clean install -pl vertx-otel-core -am
mvn clean test -pl vertx3-rxjava2-otel-autoconfigure -am
```

Always run `mvn test` before pushing.

## Module Details

| Module | Vert.x | RxJava | Package |
|--------|--------|--------|---------|
| `vertx-otel-core` | - | - | `io.last9.tracing.otel` |
| `vertx4-rxjava3-otel-autoconfigure` | 4.5.10 | 3.x | `io.last9.tracing.otel.v4` |
| `vertx3-rxjava2-otel-autoconfigure` | 3.9.16 | 2.x | `io.last9.tracing.otel.v3` |
| `vertx3-otel-agent` | - | - | `io.last9.tracing.otel.agent` |

## Release Process

### Pre-release (beta tags)
1. Commit changes to feature branch, push branch
2. Create annotated tag: `git tag -a v2.2.0-beta.N -m "description"`
3. Push tag: `git push origin v2.2.0-beta.N`
4. `.github/workflows/prerelease.yaml` triggers on `v*-beta.*` tags

### Release
- `.github/workflows/release.yaml` triggers on push to `main`
- `.github/workflows/ci.yaml` triggers on all branch pushes and PRs

### Version conventions
- **pom.xml version**: Set to the target release version (e.g., `2.2.0`), NOT SNAPSHOT
- **Git tags**: `v2.2.0-beta.N` for pre-releases, `v2.2.0` for releases
- **Maven artifact version comes from pom.xml**, not git tags — keep them aligned manually
- **Never delete/move pushed tags** — create a new beta number instead

### Git workflow
- Always use `git merge` to sync branches. **Never rebase.**
- Push only the current branch: `git push origin <branch-name>`

## Agent premain() Order — CRITICAL

The `AgentBootstrap.premain()` execution order is critical. **Transformers MUST be installed BEFORE OTel SDK init**:

```
1. Extract embedded JAR → inject onto system classloader
2. Store Instrumentation handle
3. Install ByteBuddy transformers ← BEFORE SDK init
4. Initialize OTel SDK + RxJava hooks ← AFTER transformers
```

**Why**: SDK init triggers class loading (Logback, SPI providers) which may transitively load application client classes. If transformers aren't registered yet, those classes load uninstrumented, and RETRANSFORMATION may fail silently.

## ByteBuddy Advice Rules

1. **Use `Object` for advice parameters when possible** — avoids compile-time dependencies on target libraries. Put typed logic in a separate `*Helper` class accessed via reflection or casts.

2. **All advice methods use `suppress = Throwable.class`** — instrumentation must never crash the application. Add warn-level logging in helper classes for diagnosability since failures are silent.

3. **Separate Advice from Helper** — Advice classes contain only `@Advice.OnMethodEnter`/`@Advice.OnMethodExit`. All logic goes in `*Helper` classes. ByteBuddy inlines advice bytecode; complex logic in advice causes verification errors.

4. **Log instrumentation outcomes at appropriate levels**:
   - Transformer registration: `INFO`
   - Successful transformation: `INFO` with `loaded` flag (e.g., `"transformed AerospikeClient (loaded=false)"`)
   - Instrumentation skipped (class not found): `WARN` (not debug — needs visibility)
   - Helper-level errors: `WARN`

5. **`loaded=false` is the happy path** — class intercepted before first load. `loaded=true` means retransformation, which may silently fail for some class structures.

## Route Template Extraction

When extracting JAX-RS route templates from annotations:
- **Never short-circuit annotation scanning** — `getMethods()` has no guaranteed order. Scan ALL methods matching the HTTP verb before deciding.
- **Match by segment count** — compute `remainingPath = requestPath - classPath`, count segments, match against `@Path` template segment counts.

## Three Router Types

The library instruments three distinct router patterns:
1. **RxJava2 Router** (`io.vertx.reactivex.ext.web.Router`) — route params as `:param`
2. **Core Router** (`io.vertx.ext.web.Router`) — route params as `:param`
3. **RESTEasy** (`SynchronousDispatcher.invoke()`) — route params as `{param}` (JAX-RS style)

All three must produce SERVER spans. Always test all three when changing router instrumentation.

## Code Conventions

- **Package naming**: `io.last9.tracing.otel.v4` for Vert.x 4, `io.last9.tracing.otel.v3` for Vert.x 3, `io.last9.tracing.otel` for shared core
- **Vert.x 3 uses British spelling**: `normalisedPath()` not `normalizedPath()`
- **Vert.x 3 codegen types**: `io.vertx.reactivex.*` (v3) vs `io.vertx.rxjava3.*` (v4)
- **RxJava types**: `io.reactivex.*` (v2) vs `io.reactivex.rxjava3.*` (v3)
- **Fat JAR packaging**: Each version module uses `maven-shade-plugin` to produce standalone JARs
- **Agent JAR packaging**: Uses `maven-antrun-plugin` to embed the library fat JAR as `inst/agent-impl.jar`
- **OTel semantic conventions**: Use `io.opentelemetry.semconv.HttpAttributes` and `io.opentelemetry.semconv.ServerAttributes`
- **Tracer/scope name**: `io.last9.tracing.otel.v3` for all v3 instrumentation
- **Context propagation**: SERVER spans use `Context.root()` as parent to avoid nesting under stale contexts

## Testing Patterns

- **TestOtelSetup**: Each module has a test helper that creates an in-memory OTel SDK with `InMemorySpanExporter`
- **VertxTestContext**: Tests use `@ExtendWith(VertxExtension.class)` and `VertxTestContext` for async assertions
- **RxJava plugin reset**: Tests must call `RxJavaPlugins.reset()` in `@BeforeEach` and reset the `installed` AtomicBoolean flag via reflection
- **Span waiting**: Use polling loop (`waitForSpans`) since spans are exported asynchronously
- **Port 0**: Always use `.rxListen(0)` and `server.actualPort()` to avoid port conflicts
- **AssertJ**: Use AssertJ (`assertThat`) for all assertions, not JUnit assertions

## E2E Testing

Use the example app at `java-vertx3-rxjava/auto-instrumentation/` in the `l9_otel_examples` repo:

1. Build agent JAR: `mvn package -B --no-transfer-progress -DskipTests`
2. Copy `vertx3-otel-agent/target/vertx3-otel-agent-*.jar` to example app's `lib/`
3. `docker compose build --no-cache && docker compose up -d`
4. Override `OTEL_EXPORTER_OTLP_ENDPOINT` to `http://otel-collector:4318` to see spans in collector debug output
5. Verify: `docker logs <collector> | grep -E "(Name |Kind )"`

### What to verify
- **Boot order**: Transformers installed BEFORE "SDK initialized" in agent logs
- **All transformations show `loaded=false`**
- **SERVER spans** from all 3 router types
- **CLIENT spans**: Aerospike, WebClient, Kafka, JDBC, Reactive SQL
- **Route templates**: Parameterized (`:key`, `{id}`) not literal paths
- **Log correlation**: `trace_id` and `span_id` in log lines

## Dependencies (managed in parent POM)

- OpenTelemetry SDK: 1.38.0
- OpenTelemetry Instrumentation BOM: 2.4.0 (must match SDK — mixing causes AbstractMethodError)
- OpenTelemetry Semconv: 1.25.0-alpha
- ByteBuddy: 1.14.18
- Logback: 1.5.3
- JUnit 5: 5.10.1 + AssertJ: 3.27.7

## Known Issues

- **OTel SDK + instrumentation BOM version mismatch** causes `AbstractMethodError` at runtime — always keep them aligned
- **`suppress = Throwable.class`** means instrumentation failures are silent — add warn logs in helpers
- **`autoInstallLogCorrelation()`** in OtelSdkSetup triggers Logback class loading during SDK init — this is why premain order matters
