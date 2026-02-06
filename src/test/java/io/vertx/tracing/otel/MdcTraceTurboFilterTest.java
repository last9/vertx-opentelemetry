package io.vertx.tracing.otel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.core.spi.FilterReply;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class MdcTraceTurboFilterTest {

    private TestOtelSetup otel;
    private Tracer tracer;
    private MdcTraceTurboFilter filter;

    @BeforeEach
    void setUp() {
        otel = new TestOtelSetup();
        tracer = otel.getTracer();
        filter = new MdcTraceTurboFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        otel.shutdown();
    }

    @Test
    void injectsTraceIdAndSpanIdWithValidSpan() {
        Span span = tracer.spanBuilder("test-span").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            FilterReply reply = filter.decide(null, null, Level.INFO, "msg", null, null);

            assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
            assertThat(MDC.get(MdcTraceTurboFilter.TRACE_ID_KEY))
                    .isEqualTo(span.getSpanContext().getTraceId());
            assertThat(MDC.get(MdcTraceTurboFilter.SPAN_ID_KEY))
                    .isEqualTo(span.getSpanContext().getSpanId());
        } finally {
            span.end();
        }
    }

    @Test
    void traceIdIs32HexChars() {
        Span span = tracer.spanBuilder("test-format").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            filter.decide(null, null, Level.INFO, "msg", null, null);

            assertThat(MDC.get(MdcTraceTurboFilter.TRACE_ID_KEY)).matches("[0-9a-f]{32}");
            assertThat(MDC.get(MdcTraceTurboFilter.SPAN_ID_KEY)).matches("[0-9a-f]{16}");
        } finally {
            span.end();
        }
    }

    @Test
    void doesNotSetMdcWithoutValidSpan() {
        // No active span — should not set MDC
        FilterReply reply = filter.decide(null, null, Level.INFO, "msg", null, null);

        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
        assertThat(MDC.get(MdcTraceTurboFilter.TRACE_ID_KEY)).isNull();
        assertThat(MDC.get(MdcTraceTurboFilter.SPAN_ID_KEY)).isNull();
    }

    @Test
    void doesNotClearExistingMdcWhenNoSpan() {
        // Pre-populate MDC with values from another source
        MDC.put("custom_key", "custom_value");

        filter.decide(null, null, Level.INFO, "msg", null, null);

        // Our filter should not interfere with other MDC entries
        assertThat(MDC.get("custom_key")).isEqualTo("custom_value");
    }

    @Test
    void updatesWhenSpanChanges() {
        Span span1 = tracer.spanBuilder("span-1").startSpan();
        try (Scope ignored = span1.makeCurrent()) {
            filter.decide(null, null, Level.INFO, "msg", null, null);
            assertThat(MDC.get(MdcTraceTurboFilter.TRACE_ID_KEY))
                    .isEqualTo(span1.getSpanContext().getTraceId());
        } finally {
            span1.end();
        }

        Span span2 = tracer.spanBuilder("span-2").startSpan();
        try (Scope ignored = span2.makeCurrent()) {
            filter.decide(null, null, Level.INFO, "msg", null, null);
            assertThat(MDC.get(MdcTraceTurboFilter.TRACE_ID_KEY))
                    .isEqualTo(span2.getSpanContext().getTraceId());
        } finally {
            span2.end();
        }
    }

    @Test
    void alwaysReturnsNeutral() {
        // Should return NEUTRAL for all log levels to never block logging
        assertThat(filter.decide(null, null, Level.TRACE, "msg", null, null))
                .isEqualTo(FilterReply.NEUTRAL);
        assertThat(filter.decide(null, null, Level.ERROR, "msg", null, null))
                .isEqualTo(FilterReply.NEUTRAL);
    }
}
