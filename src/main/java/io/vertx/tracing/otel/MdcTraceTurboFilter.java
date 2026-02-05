package io.vertx.tracing.otel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;
import org.slf4j.Marker;

/**
 * Logback TurboFilter that automatically injects OpenTelemetry trace context into MDC.
 *
 * <p>This filter runs before each log event and updates the MDC with the current
 * trace_id and span_id from the OpenTelemetry context. This bridges the gap between
 * Vert.x's context storage and Logback's MDC, enabling trace correlation in logs.
 *
 * <h2>Configuration</h2>
 * <p>Add this filter to your logback.xml:</p>
 * <pre>{@code
 * <configuration>
 *   <turboFilter class="io.vertx.tracing.otel.MdcTraceTurboFilter"/>
 *
 *   <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder>
 *       <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - trace_id=%X{trace_id} span_id=%X{span_id} - %msg%n</pattern>
 *     </encoder>
 *   </appender>
 *
 *   <root level="INFO">
 *     <appender-ref ref="CONSOLE"/>
 *   </root>
 * </configuration>
 * }</pre>
 *
 * <h2>Why This Is Needed</h2>
 * <p>Vert.x uses its own context storage ({@code VertxContextStorage}) instead of ThreadLocal.
 * The standard OpenTelemetry Logback MDC instrumentation expects ThreadLocal-based context,
 * which doesn't work correctly with Vert.x's event loop model. This TurboFilter bridges
 * that gap by explicitly getting the current span and injecting its context into MDC.</p>
 *
 * @see OtelLauncher
 */
public class MdcTraceTurboFilter extends TurboFilter {

    /** MDC key for trace ID */
    public static final String TRACE_ID_KEY = "trace_id";

    /** MDC key for span ID */
    public static final String SPAN_ID_KEY = "span_id";

    /**
     * Called before each log event. Injects trace context into MDC.
     *
     * @return FilterReply.NEUTRAL to allow normal logging to proceed
     */
    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        injectTraceContext();
        return FilterReply.NEUTRAL;
    }

    private void injectTraceContext() {
        Span currentSpan = Span.current();
        SpanContext spanContext = currentSpan.getSpanContext();

        if (spanContext.isValid()) {
            MDC.put(TRACE_ID_KEY, spanContext.getTraceId());
            MDC.put(SPAN_ID_KEY, spanContext.getSpanId());
        } else {
            // Clear MDC if no valid span context
            MDC.remove(TRACE_ID_KEY);
            MDC.remove(SPAN_ID_KEY);
        }
    }
}
