package io.last9.tracing.otel.v3.agent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper for {@link HttpServerAdvice}. Wraps an HTTP request handler with
 * OpenTelemetry SERVER span creation.
 *
 * <p>This operates at the {@code HttpServer.requestHandler()} level — below the
 * Router. Every incoming HTTP request gets a SERVER span, regardless of whether
 * a Vert.x Router is used. If the Router also creates a SERVER span (via
 * {@link io.last9.tracing.otel.v3.TracedRouter}), the Router's handler detects
 * the existing span and updates it (adds route pattern) instead of creating a
 * duplicate.
 */
public final class HttpServerAdviceHelper {

    private static final Logger log = LoggerFactory.getLogger(HttpServerAdviceHelper.class);
    private static final String TRACER_NAME = "io.last9.tracing.otel.v3";

    /**
     * Version marker — used to detect stale library-vs-agent classpath conflicts.
     * If the app bundles an older version of this class, the version won't match
     * the agent's expected version, revealing a classpath shadowing issue.
     */
    public static final String HELPER_VERSION = "2.3.1-beta.3";

    /**
     * Track wrapped handlers to avoid double-wrapping when requestHandler()
     * is called multiple times with the same handler.
     */
    private static final Set<Handler<?>> WRAPPED = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<Handler<?>, Boolean>()));

    private static final TextMapGetter<HttpServerRequest> HEADER_GETTER = new TextMapGetter<HttpServerRequest>() {
        @Override
        public Iterable<String> keys(HttpServerRequest carrier) {
            return carrier.headers().names();
        }

        @Override
        public String get(HttpServerRequest carrier, String key) {
            return carrier.getHeader(key);
        }
    };

    private HttpServerAdviceHelper() {}

    /** Reset state for testing. */
    static void resetForTest() {
        WRAPPED.clear();
    }

    /**
     * Wraps the given handler (if it's a Handler) with SERVER span creation.
     * Returns the original handler if it's already wrapped or not a Handler.
     */
    @SuppressWarnings("unchecked")
    public static Object wrapHandler(Object handler) {
        try {
            return doWrapHandler(handler);
        } catch (Throwable t) {
            // Log to BOTH stderr and SLF4J — stderr is always visible, SLF4J may not
            // be configured at this early stage, or may go to a file the user doesn't check.
            String msg = "HttpServerAdviceHelper: failed to wrap requestHandler — SERVER spans "
                    + "will NOT be created. Cause: " + t.getMessage() + " (" + t.getClass().getName() + ")";
            System.err.println("[Last9 OTel Agent] " + msg);
            logClassLoadingDiagnostics();
            log.warn(msg);
            return handler;
        }
    }

    /**
     * Logs diagnostic information about where this class was loaded from and which
     * GlobalOpenTelemetry class is in use. Critical for diagnosing classpath conflicts
     * when the app bundles an older version of the library alongside the agent.
     */
    private static final AtomicBoolean DIAGNOSTICS_LOGGED = new AtomicBoolean(false);

    private static void logClassLoadingDiagnostics() {
        if (!DIAGNOSTICS_LOGGED.compareAndSet(false, true)) return;
        try {
            // Where was this helper loaded from?
            java.security.CodeSource cs = HttpServerAdviceHelper.class.getProtectionDomain().getCodeSource();
            String helperSource = cs != null ? cs.getLocation().toString() : "unknown";
            System.err.println("[Last9 OTel Agent] HttpServerAdviceHelper loaded from: " + helperSource);
            System.err.println("[Last9 OTel Agent] HttpServerAdviceHelper version: " + HELPER_VERSION);

            // Where was GlobalOpenTelemetry loaded from?
            java.security.CodeSource otelCs = GlobalOpenTelemetry.class.getProtectionDomain().getCodeSource();
            String otelSource = otelCs != null ? otelCs.getLocation().toString() : "unknown";
            System.err.println("[Last9 OTel Agent] GlobalOpenTelemetry loaded from: " + otelSource);
            System.err.println("[Last9 OTel Agent] GlobalOpenTelemetry class: " + GlobalOpenTelemetry.class.getName());

            // Check if the GlobalOpenTelemetry is our shaded version
            if (!GlobalOpenTelemetry.class.getName().contains("last9.internal")) {
                System.err.println("[Last9 OTel Agent] WARNING: Using UNSHADED GlobalOpenTelemetry! "
                        + "This likely means the app has an older version of vertx3-rxjava2-otel-autoconfigure "
                        + "on its classpath that shadows the agent's shaded classes. "
                        + "Fix: remove the io.last9:vertx3-rxjava2-otel-autoconfigure dependency from "
                        + "your pom.xml — the agent is self-contained and does not need it.");
            }
        } catch (Throwable diag) {
            System.err.println("[Last9 OTel Agent] Failed to collect diagnostics: " + diag.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Object doWrapHandler(Object handler) {
        if (handler == null) {
            return null;
        }
        if (!(handler instanceof Handler)) {
            return handler;
        }
        Handler<HttpServerRequest> original = (Handler<HttpServerRequest>) handler;
        if (!WRAPPED.add(original)) {
            // Already wrapped
            return handler;
        }

        log.info("HttpServerAdviceHelper: wrapping requestHandler with SERVER span creation");

        // Capture tracer and propagator once at wrap time (server startup), not per-request.
        final Tracer tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME);
        final TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();

        // Diagnostic: verify tracer is not no-op (which would silently produce zero spans)
        String tracerClass = tracer.getClass().getName();
        boolean isNoop = tracerClass.contains("Noop") || tracerClass.endsWith("DefaultTracer");
        if (isNoop) {
            String msg = "HttpServerAdviceHelper: Tracer is NO-OP (" + tracerClass + ") — "
                    + "SERVER spans will be created but silently discarded. "
                    + "This usually means GlobalOpenTelemetry was not initialized with a real SDK. "
                    + "Check if the app bundles an older version of vertx3-rxjava2-otel-autoconfigure "
                    + "that shadows the agent's shaded classes.";
            System.err.println("[Last9 OTel Agent] " + msg);
            logClassLoadingDiagnostics();
            log.warn(msg);
        } else {
            System.err.println("[Last9 OTel Agent] HttpServerAdviceHelper: Tracer OK (" + tracerClass + ")");
        }

        Handler<HttpServerRequest> wrapped = new Handler<HttpServerRequest>() {
            @Override
            public void handle(HttpServerRequest request) {

                // Check if Netty-level instrumentation already created a SERVER span.
                // If so, adopt it (manage scope only) — don't create a duplicate.
                Span nettySpan = NettyServerTracingHandler.NETTY_SERVER_SPAN.get();
                if (nettySpan != null && nettySpan.getSpanContext().isValid()) {
                    NettyServerTracingHandler.NETTY_SERVER_SPAN.remove();
                    // Enrich the Netty span with Vert.x-level attributes
                    nettySpan.setAttribute("url.scheme", request.isSSL() ? "https" : "http");
                    // Make Netty span current within this handler scope, then delegate.
                    // Netty handler manages span lifecycle (end on response write).
                    Context otelContext = Context.root().with(nettySpan);
                    try (Scope ignored = otelContext.makeCurrent()) {
                        original.handle(request);
                    }
                    return;
                }

                // No Netty span — create our own SERVER span (original path).
                String method = request.method().name();
                String path = request.path();

                Context parentContext = propagator.extract(Context.root(), request, HEADER_GETTER);

                // Parse host header for server.address and server.port
                String hostHeader = request.host();
                String serverAddr = hostHeader;
                long serverPort = -1;
                if (hostHeader != null && hostHeader.contains(":")) {
                    int idx = hostHeader.lastIndexOf(':');
                    serverAddr = hostHeader.substring(0, idx);
                    try {
                        serverPort = Long.parseLong(hostHeader.substring(idx + 1));
                    } catch (NumberFormatException ignored) {
                        // keep -1
                    }
                }

                Span span = tracer.spanBuilder(method + " " + path)
                        .setParent(parentContext)
                        .setSpanKind(SpanKind.SERVER)
                        .setAttribute("http.request.method", method)
                        .setAttribute("url.path", path)
                        .setAttribute("url.scheme",
                                request.isSSL() ? "https" : "http")
                        .setAttribute("server.address", serverAddr)
                        .startSpan();

                if (serverPort > 0) {
                    span.setAttribute(AttributeKey.longKey("server.port"), serverPort);
                }

                // Set response attributes and end span when body is fully sent.
                // The span is NOT ended here — it's ended in bodyEndHandler after the
                // response is fully sent. But the SCOPE must be closed immediately after
                // the handler returns to avoid context leaks on the event loop thread.
                HttpServerResponse response = request.response();
                response.headersEndHandler(v -> {
                    int statusCode = response.getStatusCode();
                    span.setAttribute(AttributeKey.longKey("http.response.status_code"), (long) statusCode);
                    if (statusCode >= 500) {
                        span.setStatus(StatusCode.ERROR);
                    }
                });

                response.bodyEndHandler(v -> span.end());

                // Handle connection reset / close before response
                response.closeHandler(v -> {
                    if (!response.ended()) {
                        span.setStatus(StatusCode.ERROR, "Connection closed before response completed");
                        span.end();
                    }
                });

                // Make span current, delegate to original handler, then close scope.
                // CRITICAL: scope must close when handle() returns — NOT in bodyEndHandler.
                Context otelContext = parentContext.with(span);
                try (Scope ignored = otelContext.makeCurrent()) {
                    original.handle(request);
                }
            }
        };

        // Track the wrapped handler too so re-wrapping is prevented
        WRAPPED.add(wrapped);
        return wrapped;
    }
}
