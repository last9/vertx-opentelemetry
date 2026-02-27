package io.last9.tracing.otel.v4;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures Vert.x event loop lag: the delay between when a periodic task is
 * <em>scheduled</em> and when it actually <em>executes</em>.
 *
 * <p>A blocked or overloaded event loop causes tasks to queue up. This probe
 * detects that by comparing the actual execution time against the expected
 * time. Sustained lag above ~50ms typically indicates the event loop is being
 * blocked by synchronous operations.
 *
 * <p>The metric {@code vertx.eventloop.lag} (unit: ms) is a Micrometer
 * {@link Gauge} that bridges into the OTel {@link MeterRegistry} via the
 * {@code opentelemetry-micrometer-1.5} shim.
 *
 * <h2>Implementation note</h2>
 * {@code vertx-micrometer-metrics} exposes {@code vertx.eventloop.pending.tasks}
 * (queue depth) but not actual lag time. This probe fills that gap using a
 * {@code setPeriodic} timer running on the Vert.x event loop itself.
 */
public final class EventLoopLagProbe {

    private static final Logger log = LoggerFactory.getLogger(EventLoopLagProbe.class);

    /** How often the lag probe runs. 100ms is fine-grained enough for production alerting
     *  while adding negligible event-loop load. */
    private static final long PROBE_INTERVAL_MS = 100;

    private EventLoopLagProbe() {
    }

    /**
     * Install the event loop lag probe.
     *
     * <p>Registers a {@code vertx.eventloop.lag} gauge and schedules a periodic
     * task on the Vert.x event loop that measures scheduling delay.
     *
     * @param vertx    the Vert.x instance (must be fully started)
     * @param registry the Micrometer registry bridged to OTel
     */
    public static void install(Vertx vertx, MeterRegistry registry) {
        AtomicLong lagMs = new AtomicLong(0);

        Gauge.builder("vertx.eventloop.lag", lagMs, AtomicLong::get)
                .description("Event loop lag in milliseconds — time between task scheduling and execution. "
                        + "Sustained values above 50ms indicate the event loop is being blocked.")
                .baseUnit("ms")
                .register(registry);

        // Schedule a recurring probe on the event loop.
        // Each iteration records: actual execution time − (last execution + interval) = lag.
        long[] lastRun = {System.currentTimeMillis()};
        vertx.setPeriodic(PROBE_INTERVAL_MS, id -> {
            long now = System.currentTimeMillis();
            long lag = now - (lastRun[0] + PROBE_INTERVAL_MS);
            lagMs.set(Math.max(0, lag));
            lastRun[0] = now;
        });

        log.info("Event loop lag probe installed (interval: {}ms)", PROBE_INTERVAL_MS);
    }
}
