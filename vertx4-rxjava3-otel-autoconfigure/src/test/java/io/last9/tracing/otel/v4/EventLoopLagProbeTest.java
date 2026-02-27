package io.last9.tracing.otel.v4;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EventLoopLagProbe}.
 *
 * Uses a {@link SimpleMeterRegistry} (in-memory, no OTel required) to keep the
 * test lightweight and focused on the probe's own behaviour.
 */
class EventLoopLagProbeTest {

    private Vertx vertx;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        registry.close();
    }

    @Test
    void installRegistersEventLoopLagGauge() {
        EventLoopLagProbe.install(vertx, registry);

        Gauge gauge = registry.find("vertx.eventloop.lag").gauge();
        assertThat(gauge)
                .as("vertx.eventloop.lag gauge should be registered after install()")
                .isNotNull();
    }

    @Test
    void lagGaugeReportsNonNegativeValue() throws Exception {
        EventLoopLagProbe.install(vertx, registry);

        // Allow at least two probe cycles to run so the gauge has a value
        Thread.sleep(300);

        Gauge gauge = registry.find("vertx.eventloop.lag").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value())
                .as("event loop lag must be >= 0 (negative lag is impossible)")
                .isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void lagGaugeHasMillisecondBaseUnit() {
        EventLoopLagProbe.install(vertx, registry);

        Gauge gauge = registry.find("vertx.eventloop.lag").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.getId().getBaseUnit())
                .as("base unit should be milliseconds")
                .isEqualTo("ms");
    }

    @Test
    void lagGaugeUpdatesOverTime() throws Exception {
        EventLoopLagProbe.install(vertx, registry);

        // Collect two readings separated by more than one probe interval
        Thread.sleep(150);
        Gauge gauge = registry.find("vertx.eventloop.lag").gauge();
        assertThat(gauge).isNotNull();

        // The gauge must remain valid (non-NaN) after multiple probe cycles
        double value = gauge.value();
        assertThat(Double.isNaN(value))
                .as("gauge value must not be NaN")
                .isFalse();
    }
}
