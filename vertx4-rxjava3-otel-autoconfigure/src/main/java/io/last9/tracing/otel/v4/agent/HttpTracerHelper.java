package io.last9.tracing.otel.v4.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enriches CLIENT HTTP spans created by {@code io.vertx.tracing.opentelemetry.OpenTelemetryTracer}
 * with HTTP path, status code, and error status.
 *
 * <p>The {@code vertx-opentelemetry} SPI creates CLIENT spans named only with the HTTP method
 * (e.g., {@code "GET"}), and never sets {@code http.status_code} or {@code StatusCode.ERROR}
 * for 4xx/5xx responses. This helper enriches those spans after the fact.
 *
 * <p>The SPI span is an {@code io.opentelemetry.api.trace.Span} from the <em>app's</em>
 * OpenTelemetry SDK — a different classloader scope from our relocated
 * {@code io.last9.internal.otel} classes. All span mutations are therefore performed via
 * reflection so that no import dependency on the app's OTel types is required.
 */
public final class HttpTracerHelper {

    private static final Logger log = LoggerFactory.getLogger(HttpTracerHelper.class);

    private HttpTracerHelper() {}

    /**
     * Called from {@link HttpClientSendAdvice} on exit of
     * {@code OpenTelemetryTracer.sendRequest()}.
     *
     * <p>Updates the span name from the bare HTTP method to
     * {@code "{method} {path}"} (e.g., {@code "GET /api/v1/users"}) and adds
     * {@code http.url}, {@code net.peer.name}, and {@code net.peer.port} attributes.
     *
     * @param request   the {@code io.vertx.core.http.HttpClientRequest} instance
     * @param operation the {@code io.vertx.tracing.opentelemetry.Operation} returned by
     *                  {@code sendRequest()} — wraps the app's {@code Span}
     */
    public static void enrichOnSend(Object request, Object operation) {
        if (operation == null || !isHttpClientRequest(request)) return;
        try {
            Object span = operation.getClass().getMethod("span").invoke(operation);
            if (span == null) return;
            if (!isRecording(span)) return;

            String method = extractMethod(request);
            String path   = extractPath(request);
            String host   = extractHost(request);
            int    port   = extractPort(request);

            String newName = method + (path != null ? " " + path : "");
            span.getClass().getMethod("updateName", String.class).invoke(span, newName);

            if (host != null) {
                setAttribute(span, "net.peer.name", host);
            }
            if (port > 0) {
                setAttribute(span, "net.peer.port", (long) port);
            }
            String uri = extractUri(request);
            if (uri != null) {
                setAttribute(span, "http.url", buildUrl(host, port, uri));
            }
        } catch (Exception e) {
            log.warn("HttpTracerHelper: failed to enrich CLIENT span on send: {}", e.getMessage());
        }
    }

    /**
     * Called from {@link HttpClientReceiveAdvice} on entry of
     * {@code OpenTelemetryTracer.receiveResponse()}, before the SPI ends the span.
     *
     * <p>Adds {@code http.status_code} and sets {@code StatusCode.ERROR} for
     * 4xx/5xx responses or exceptions.
     *
     * @param response  the {@code io.vertx.core.http.HttpClientResponse} instance (may be null)
     * @param operation the {@code io.vertx.tracing.opentelemetry.Operation} — wraps the span
     * @param failure   non-null if the request failed with an exception
     */
    public static void enrichOnReceive(Object response, Object operation, Throwable failure) {
        if (operation == null) return;
        if (response != null && !isHttpClientResponse(response)) return;
        try {
            Object span = operation.getClass().getMethod("span").invoke(operation);
            if (span == null) return;
            if (!isRecording(span)) return;

            int statusCode = -1;
            if (response != null) {
                try {
                    Object code = response.getClass().getMethod("statusCode").invoke(response);
                    if (code instanceof Integer) statusCode = (Integer) code;
                } catch (Exception ignored) {}
            }

            if (statusCode > 0) {
                setAttribute(span, "http.status_code", (long) statusCode);
            }

            if (failure != null || statusCode >= 400) {
                setErrorStatus(span, failure, statusCode);
            }
        } catch (Exception e) {
            log.warn("HttpTracerHelper: failed to enrich CLIENT span on receive: {}", e.getMessage());
        }
    }

    // --- reflection helpers ---

    private static boolean isRecording(Object span) {
        try {
            return Boolean.TRUE.equals(span.getClass().getMethod("isRecording").invoke(span));
        } catch (Exception e) {
            return false;
        }
    }

    private static void setAttribute(Object span, String key, String value) throws Exception {
        span.getClass().getMethod("setAttribute", String.class, String.class)
                .invoke(span, key, value);
    }

    private static void setAttribute(Object span, String key, long value) throws Exception {
        span.getClass().getMethod("setAttribute", String.class, long.class)
                .invoke(span, key, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setErrorStatus(Object span, Throwable failure, int statusCode) {
        try {
            ClassLoader cl = span.getClass().getClassLoader();
            Class<?> statusCodeEnum = cl.loadClass("io.opentelemetry.api.trace.StatusCode");
            Object errorCode = Enum.valueOf((Class<Enum>) statusCodeEnum, "ERROR");
            String desc = failure != null ? failure.getMessage() : "HTTP " + statusCode;
            if (desc == null) desc = "error";
            span.getClass().getMethod("setStatus", statusCodeEnum, String.class)
                    .invoke(span, errorCode, desc);
        } catch (Exception e) {
            log.warn("HttpTracerHelper: failed to set ERROR status: {}", e.getMessage());
        }
    }

    // --- request/response type guards ---

    private static boolean isHttpClientRequest(Object obj) {
        return implementsInterface(obj, "io.vertx.core.http.HttpClientRequest");
    }

    private static boolean isHttpClientResponse(Object obj) {
        return implementsInterface(obj, "io.vertx.core.http.HttpClientResponse");
    }

    private static boolean implementsInterface(Object obj, String ifaceName) {
        if (obj == null) return false;
        Class<?> c = obj.getClass();
        while (c != null) {
            for (Class<?> iface : c.getInterfaces()) {
                if (ifaceName.equals(iface.getName())) return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    // --- request field extractors ---

    private static String extractMethod(Object request) {
        try {
            Object m = request.getClass().getMethod("method").invoke(request);
            return m != null ? m.toString() : "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /** Returns the path portion of the URI (no query string). */
    private static String extractPath(Object request) {
        // Prefer path() which excludes query string; fall back to parsing uri()
        try {
            Object p = request.getClass().getMethod("path").invoke(request);
            if (p instanceof String && !((String) p).isEmpty()) return (String) p;
        } catch (Exception ignored) {}
        String uri = extractUri(request);
        if (uri == null) return null;
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }

    private static String extractUri(Object request) {
        try {
            Object u = request.getClass().getMethod("uri").invoke(request);
            return u instanceof String ? (String) u : null;
        } catch (Exception ignored) {}
        try {
            Object u = request.getClass().getMethod("getURI").invoke(request);
            return u instanceof String ? (String) u : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractHost(Object request) {
        try {
            Object h = request.getClass().getMethod("getHost").invoke(request);
            if (h instanceof String && !((String) h).isEmpty()) return (String) h;
        } catch (Exception ignored) {}
        try {
            Object h = request.getClass().getMethod("host").invoke(request);
            return h instanceof String ? (String) h : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int extractPort(Object request) {
        try {
            Object p = request.getClass().getMethod("getPort").invoke(request);
            if (p instanceof Integer) return (Integer) p;
        } catch (Exception ignored) {}
        try {
            Object p = request.getClass().getMethod("port").invoke(request);
            return p instanceof Integer ? (Integer) p : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String buildUrl(String host, int port, String uri) {
        if (host == null) return uri;
        String scheme = (port == 443) ? "https" : "http";
        String portSuffix = (port == 80 || port == 443 || port <= 0) ? "" : ":" + port;
        return scheme + "://" + host + portSuffix + uri;
    }
}
