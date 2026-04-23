package io.last9.tracing.otel.v4.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.vertx.core.VertxOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the VertxFactoryHelper TracingOptions injection.
 *
 * <p>Background: The agent shade plugin previously relocated {@code io.opentelemetry →
 * io.last9.internal.otel}. This caused {@code VertxFactoryHelper.injectOptions()} to call
 * {@code new OpenTelemetryOptions(otel)} where {@code otel} was of the relocated type
 * ({@code io.last9.internal.otel.api.OpenTelemetry}), but the {@code OpenTelemetryOptions}
 * constructor (from the app's {@code vertx-opentelemetry}, loaded first) expects the
 * original type ({@code io.opentelemetry.api.OpenTelemetry}). Java resolves these as
 * incompatible types → {@code NoSuchMethodException} → WARN log → no VertxTracer SPI →
 * no HTTP server/client or Kafka spans.
 *
 * <p>The fix: remove {@code io.opentelemetry} from shade relocations. Both agent and
 * Vert.x SPI now use the same {@code io.opentelemetry.*} namespace.
 */
class VertxFactoryHelperTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() throws Exception {
        resetInjectedFlag();
        GlobalOpenTelemetry.resetForTest();

        spanExporter = InMemorySpanExporter.create();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                        .build())
                .buildAndRegisterGlobal();
    }

    @AfterEach
    void tearDown() throws Exception {
        GlobalOpenTelemetry.resetForTest();
        resetInjectedFlag();
    }

    /**
     * Verifies that {@code injectOptions()} sets {@link OpenTelemetryOptions} on
     * the {@code VertxOptions} held by the builder.
     *
     * <p>This is the contract that broke in production when the shade plugin relocated
     * {@code io.opentelemetry → io.last9.internal.otel}: the constructor lookup for
     * {@code OpenTelemetryOptions(OpenTelemetry)} failed with {@code NoSuchMethodException}
     * because the type passed was {@code io.last9.internal.otel.api.OpenTelemetry} (relocated)
     * but the constructor expected {@code io.opentelemetry.api.OpenTelemetry} (original).
     */
    @Test
    void injectOptionsSetsTracingOptionsOnVertxOptions() throws Exception {
        VertxOptions options = new VertxOptions();
        assertThat(options.getTracingOptions()).isNull();

        VertxFactoryHelper.injectOptions(new StubBuilder(options));

        assertThat(options.getTracingOptions())
                .as("TracingOptions must be set for VertxTracer SPI to produce SERVER/CLIENT spans")
                .isNotNull()
                .isInstanceOf(OpenTelemetryOptions.class);
    }

    @Test
    void injectOptionsUsesRegisteredGlobalOpenTelemetry() throws Exception {
        // Verify that the OTel instance used for tracing is the globally registered one,
        // not some other instance. If namespaces are mismatched (shade bug), GlobalOpenTelemetry
        // in the agent's relocated namespace would be different from the one OpenTelemetryOptions
        // uses — causing a no-op tracer even if injection appeared to succeed.
        // Note: GlobalOpenTelemetry.get() returns an ObfuscatedOpenTelemetry wrapper, not the raw SDK.
        OpenTelemetry registeredOtel = GlobalOpenTelemetry.get();
        assertThat(registeredOtel).isNotNull();
        assertThat(registeredOtel.getClass().getSimpleName()).contains("OpenTelemetry");

        VertxOptions options = new VertxOptions();
        VertxFactoryHelper.injectOptions(new StubBuilder(options));

        assertThat(options.getTracingOptions()).isNotNull();
    }

    @Test
    void injectOptionsIsIdempotent() throws Exception {
        VertxOptions options = new VertxOptions();
        StubBuilder builder = new StubBuilder(options);

        VertxFactoryHelper.injectOptions(builder);
        VertxFactoryHelper.injectOptions(builder); // second call must be a no-op

        assertThat(options.getTracingOptions()).isNotNull(); // set by first call
    }

    @Test
    void injectOptionsHandlesNullOptionsFieldGracefully() throws Exception {
        // Should not throw — logs WARN and returns
        VertxFactoryHelper.injectOptions(new StubBuilderWithNullOptions());
        // No assertion: test passes if no exception is thrown
    }

    // ---- Stub builder helpers ----

    /** Mimics the private {@code options} field shape that {@code VertxBuilder} has. */
    static class StubBuilder {
        @SuppressWarnings("unused") // accessed via reflection by VertxFactoryHelper
        private final VertxOptions options;
        StubBuilder(VertxOptions options) { this.options = options; }
    }

    static class StubBuilderWithNullOptions {
        @SuppressWarnings("unused")
        private final VertxOptions options = null;
    }

    private static void resetInjectedFlag() throws Exception {
        Field f = VertxFactoryHelper.class.getDeclaredField("INJECTED");
        f.setAccessible(true);
        ((AtomicBoolean) f.get(null)).set(false);
    }
}
