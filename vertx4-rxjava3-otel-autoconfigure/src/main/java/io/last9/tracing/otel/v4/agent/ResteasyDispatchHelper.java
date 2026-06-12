package io.last9.tracing.otel.v4.agent;

import io.last9.tracing.otel.v4.BodyCaptureConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Helper methods called by {@link ResteasyDispatchAdvice} to create SERVER spans
 * for JAX-RS requests dispatched by RESTEasy on Vert.x.
 *
 * <p>All RESTEasy types ({@code HttpRequest}, {@code HttpResponse}, {@code UriInfo},
 * {@code HttpHeaders}) are accessed via reflection so that this library has no
 * compile-time dependency on RESTEasy. This works across RESTEasy 3.x and 4.x.
 *
 * <p>The OTel context (with the SERVER span) is made current via a ThreadLocal
 * {@link Scope}. This is safe because {@code SynchronousDispatcher.invoke()} is
 * synchronous — the enter and exit run on the same thread with no interleaving.
 */
public final class ResteasyDispatchHelper {

    private static final String TRACER_NAME = "io.last9.tracing.otel.v4";

    private static final ThreadLocal<Scope> SCOPE_HOLDER = new ThreadLocal<>();
    private static final AtomicBoolean URI_INFO_LOG_ONCE = new AtomicBoolean(false);

    // Request attribute keys — stored on HttpRequest so they survive async thread hops.
    private static final String ATTR_SPAN           = "io.last9.otel.span";
    private static final String ATTR_REQ_BODY       = "io.last9.otel.req.body";
    private static final String ATTR_RESP_CAPTURE   = "io.last9.otel.resp.capture";
    // Stored by ResteasyWriteExceptionAdvice.onEnter so endSpanFromAsync can read the
    // throwable even when asynchronousDelivery is called from inside writeException.
    private static final String ATTR_ASYNC_EXCEPTION = "io.last9.otel.async.exception";

    /**
     * Extracts W3C trace context headers from a RESTEasy HttpRequest via reflection.
     * The carrier is the HttpRequest object; headers are accessed via
     * {@code getHttpHeaders().getHeaderString(name)} and
     * {@code getHttpHeaders().getRequestHeaders()}.
     */
    private static final TextMapGetter<Object> HEADER_GETTER = new TextMapGetter<Object>() {
        @Override
        public Iterable<String> keys(Object carrier) {
            try {
                Object headers = carrier.getClass().getMethod("getHttpHeaders").invoke(carrier);
                @SuppressWarnings("unchecked")
                Map<String, List<String>> map = (Map<String, List<String>>)
                        headers.getClass().getMethod("getRequestHeaders").invoke(headers);
                return map != null ? map.keySet() : Collections.emptyList();
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        @Override
        public String get(Object carrier, String key) {
            try {
                Object headers = carrier.getClass().getMethod("getHttpHeaders").invoke(carrier);
                return (String) headers.getClass()
                        .getMethod("getHeaderString", String.class)
                        .invoke(headers, key);
            } catch (Exception e) {
                return null;
            }
        }
    };

    private ResteasyDispatchHelper() {}

    /**
     * Creates a SERVER span from the RESTEasy HttpRequest and makes the OTel
     * context current. The scope is stored in a ThreadLocal for cleanup in
     * {@link #endSpan}.
     *
     * @param requestObj the RESTEasy HttpRequest (accessed via reflection)
     * @return the span, or null if creation failed
     */
    public static Span startSpan(Object requestObj) {
        try {
            String method = (String) requestObj.getClass()
                    .getMethod("getHttpMethod").invoke(requestObj);
            Object uriInfo = requestObj.getClass()
                    .getMethod("getUri").invoke(requestObj);
            String path = (String) uriInfo.getClass()
                    .getMethod("getPath").invoke(uriInfo);

            if (method == null) method = "UNKNOWN";
            if (path == null) path = "/";

            OpenTelemetry otel = GlobalOpenTelemetry.get();
            Tracer tracer = otel.getTracer(TRACER_NAME);
            TextMapPropagator propagator = otel.getPropagators().getTextMapPropagator();

            Context parentContext = propagator.extract(Context.root(), requestObj, HEADER_GETTER);

            Span span = tracer.spanBuilder(method + " " + path)
                    .setParent(parentContext)
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("http.request.method", method)
                    .setAttribute("url.path", path)
                    .startSpan();

            // Extract additional HTTP semantic attributes from the request URI
            try {
                Object requestUri = uriInfo.getClass().getMethod("getRequestUri").invoke(uriInfo);
                if (requestUri instanceof URI) {
                    URI uri = (URI) requestUri;
                    if (uri.getScheme() != null) {
                        span.setAttribute("url.scheme", uri.getScheme());
                    }
                    if (uri.getHost() != null) {
                        span.setAttribute("server.address", uri.getHost());
                    }
                    if (uri.getPort() > 0) {
                        span.setAttribute("server.port", (long) uri.getPort());
                    }
                    if (uri.getQuery() != null) {
                        span.setAttribute("url.query", uri.getQuery());
                    }
                    span.setAttribute("url.full", uri.toString());
                }
            } catch (Exception e) {
                if (URI_INFO_LOG_ONCE.compareAndSet(false, true)) {
                    System.err.println("[last9-otel] getRequestUri() unavailable: " + e.getMessage());
                }
                // fallback: try getAbsolutePath() for query string
                try {
                    Object absUri = uriInfo.getClass().getMethod("getAbsolutePath").invoke(uriInfo);
                    if (absUri instanceof URI) {
                        URI abs = (URI) absUri;
                        if (abs.getQuery() != null) span.setAttribute("url.query", abs.getQuery());
                        if (abs.getScheme() != null) span.setAttribute("url.scheme", abs.getScheme());
                        if (abs.getHost() != null) span.setAttribute("server.address", abs.getHost());
                        if (abs.getPort() > 0) span.setAttribute("server.port", (long) abs.getPort());
                        span.setAttribute("url.full", abs.toString());
                    }
                } catch (Exception ignored) {}
            }

            // User-Agent header
            try {
                Object headers = requestObj.getClass().getMethod("getHttpHeaders").invoke(requestObj);
                String userAgent = (String) headers.getClass()
                        .getMethod("getHeaderString", String.class).invoke(headers, "User-Agent");
                if (userAgent != null) {
                    span.setAttribute("user_agent.original", userAgent);
                }
            } catch (Exception ignored) {}

            // Request body capture — two paths depending on whether the stream supports mark/reset.
            // Undertow's ServletInputStream does NOT support mark/reset; Vert.x's buffered body does.
            if (BodyCaptureConfig.enabled() && BodyCaptureConfig.captureRequest()) {
                try {
                    InputStream is = (InputStream) requestObj.getClass()
                            .getMethod("getInputStream").invoke(requestObj);
                    if (is != null) {
                        int max = BodyCaptureConfig.maxBytes();
                        byte[] buf = new byte[max];
                        if (is.markSupported()) {
                            // Non-destructive: mark → read → reset
                            is.mark(max + 1);
                            int read = is.read(buf, 0, max);
                            if (read > 0) {
                                setReqAttr(requestObj, ATTR_REQ_BODY,
                                        read == max ? buf : java.util.Arrays.copyOf(buf, read));
                            }
                            is.reset();
                        } else {
                            // Destructive read: capture bytes, restore stream via setInputStream()
                            int total = 0, n;
                            while (total < max && (n = is.read(buf, total, max - total)) != -1) {
                                total += n;
                            }
                            if (total > 0) {
                                byte[] captured = total == max ? buf : java.util.Arrays.copyOf(buf, total);
                                setReqAttr(requestObj, ATTR_REQ_BODY, captured);
                                // Restore: captured bytes + any remaining (truncated) stream
                                InputStream restored = total < max
                                        ? new ByteArrayInputStream(captured)
                                        : new SequenceInputStream(new ByteArrayInputStream(captured), is);
                                requestObj.getClass()
                                        .getMethod("setInputStream", InputStream.class)
                                        .invoke(requestObj, restored);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Store on request attributes so async delivery advice can retrieve it
            // across thread boundaries (ThreadLocal won't survive the pool-thread hop).
            setReqAttr(requestObj, ATTR_SPAN, span);

            Context otelContext = parentContext.with(span);
            Scope scope = otelContext.makeCurrent();
            SCOPE_HOLDER.set(scope);

            return span;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ends the SERVER span, recording the response status, http.route, and any exception.
     * Closes the OTel scope stored by {@link #startSpan}.
     *
     * @param span        the span from startSpan (nullable)
     * @param requestObj  the RESTEasy HttpRequest (for extracting matched route, nullable)
     * @param responseObj the RESTEasy HttpResponse (accessed via reflection, nullable)
     * @param thrown      exception thrown during dispatch (nullable)
     */
    public static void endSpan(Span span, Object requestObj, Object responseObj, Throwable thrown) {
        if (span == null) return;
        if (!span.isRecording()) {
            // Already ended (e.g. by writeException advice for sync errors); just clean up scope.
            Scope scope = SCOPE_HOLDER.get();
            if (scope != null) { scope.close(); SCOPE_HOLDER.remove(); }
            return;
        }

        // For async exceptions RESTEasy 4.x may have stored the throwable before reaching this path.
        Throwable effectiveThrown = thrown;
        if (effectiveThrown == null && requestObj != null) {
            effectiveThrown = (Throwable) getReqAttr(requestObj, ATTR_ASYNC_EXCEPTION);
        }

        int status = -1;
        try {
            if (effectiveThrown != null) {
                span.recordException(effectiveThrown,
                        Attributes.of(AttributeKey.booleanKey("exception.escaped"), true));
                span.setStatus(StatusCode.ERROR, effectiveThrown.getMessage());
            }

            if (responseObj != null) {
                try {
                    Object statusObj = responseObj.getClass()
                            .getMethod("getStatus").invoke(responseObj);
                    status = (int) statusObj;
                } catch (Exception ignored) {
                    // Response may not support getStatus() in some RESTEasy versions
                }
            }

            // When an exception escaped, the response status read above may still be the
            // uncommitted default (200) because the async error path ends the span before
            // RESTEasy commits the error code via unhandledAsynchronousException. Derive the
            // real code from the throwable: a WebApplicationException carries its own status,
            // otherwise RESTEasy maps unhandled exceptions to 500.
            if (effectiveThrown != null && status < 400) {
                status = statusFromThrowable(effectiveThrown);
            }

            if (status > 0) {
                span.setAttribute("http.response.status_code", (long) status);
                if (status >= 500) {
                    span.setStatus(StatusCode.ERROR);
                }
            }

            // Extract JAX-RS route from matched resource @Path annotations
            if (requestObj != null) {
                String route = extractJaxRsRoute(requestObj);
                if (route != null) {
                    span.setAttribute("http.route", route);
                    String method = null;
                    try {
                        method = (String) requestObj.getClass()
                                .getMethod("getHttpMethod").invoke(requestObj);
                    } catch (Exception ignored) {}
                    if (method != null) {
                        span.updateName(method + " " + route);
                    }
                }
            }

            // Attach captured request body (stored on request attributes for async thread safety).
            byte[] reqBody = (byte[]) getReqAttr(requestObj, ATTR_REQ_BODY);
            if (reqBody != null && BodyCaptureConfig.enabled()) {
                boolean shouldAttach = !BodyCaptureConfig.errorOnly() || (status >= 400) || (effectiveThrown != null);
                if (shouldAttach) {
                    String ct = getRequestContentType(requestObj);
                    String reqPath = getRequestPath(requestObj);
                    if (BodyCaptureConfig.isAllowedContentType(ct) && BodyCaptureConfig.isAllowedPath(reqPath)) {
                        span.setAttribute("http.request.body",
                                new String(reqBody, StandardCharsets.UTF_8));
                    }
                }
            }

            // Attach captured response body.
            ByteArrayOutputStream respCapture = (ByteArrayOutputStream) getReqAttr(requestObj, ATTR_RESP_CAPTURE);
            if (respCapture != null && respCapture.size() > 0
                    && BodyCaptureConfig.enabled() && BodyCaptureConfig.captureResponse()) {
                boolean shouldAttach = !BodyCaptureConfig.errorOnly() || (status >= 400) || (effectiveThrown != null);
                if (shouldAttach) {
                    String ct = getResponseContentType(responseObj);
                    String reqPath = getRequestPath(requestObj);
                    if (BodyCaptureConfig.isAllowedContentType(ct) && BodyCaptureConfig.isAllowedPath(reqPath)) {
                        span.setAttribute("http.response.body",
                                new String(respCapture.toByteArray(), StandardCharsets.UTF_8));
                    }
                }
            }
        } finally {
            Scope scope = SCOPE_HOLDER.get();
            if (scope != null) {
                scope.close();
                SCOPE_HOLDER.remove();
            }
            span.end();
        }
    }

    private static String getRequestContentType(Object requestObj) {
        if (requestObj == null) return null;
        try {
            Object headers = requestObj.getClass().getMethod("getHttpHeaders").invoke(requestObj);
            return (String) headers.getClass()
                    .getMethod("getHeaderString", String.class).invoke(headers, "Content-Type");
        } catch (Exception e) {
            return null;
        }
    }

    private static String getRequestPath(Object requestObj) {
        if (requestObj == null) return null;
        try {
            Object uriInfo = requestObj.getClass().getMethod("getUri").invoke(requestObj);
            return (String) uriInfo.getClass().getMethod("getPath").invoke(uriInfo);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the JAX-RS route template from matched resource classes.
     * Uses reflection to call {@code UriInfo.getMatchedResources()} and then
     * reads {@code @Path} annotations from the matched resource class and its methods.
     *
     * @param requestObj the RESTEasy HttpRequest
     * @return the route template (e.g., "/api/v1/contests/{id}/leaderboard"), or null
     */
    private static String extractJaxRsRoute(Object requestObj) {
        try {
            Object uriInfo = requestObj.getClass().getMethod("getUri").invoke(requestObj);
            if (uriInfo == null) return null;

            // Get matched resource instances (ordered from most specific to root)
            @SuppressWarnings("unchecked")
            List<Object> matchedResources = (List<Object>) uriInfo.getClass()
                    .getMethod("getMatchedResources").invoke(uriInfo);
            if (matchedResources == null || matchedResources.isEmpty()) return null;

            // The first matched resource is the most specific (the one handling the request)
            Object resource = matchedResources.get(0);
            Class<?> resourceClass = resource.getClass();

            // Get class-level @Path
            String classPath = getPathAnnotationValue(resourceClass);

            // Get the actual request path and HTTP method to find the matching method
            String httpMethod = (String) requestObj.getClass()
                    .getMethod("getHttpMethod").invoke(requestObj);
            String requestPath = (String) uriInfo.getClass()
                    .getMethod("getPath").invoke(uriInfo);

            // Find the method that matches the request by checking JAX-RS annotations
            String methodPath = findMatchingMethodPath(resourceClass, httpMethod, requestPath, classPath);

            if (classPath == null && methodPath == null) return null;

            StringBuilder route = new StringBuilder();
            if (classPath != null) {
                if (!classPath.startsWith("/")) route.append("/");
                route.append(classPath);
            }
            if (methodPath != null) {
                if (route.length() > 0 && !methodPath.startsWith("/")) route.append("/");
                route.append(methodPath);
            }

            // Normalize: convert {param} to JAX-RS template format (already correct)
            String result = route.toString();
            // Ensure single leading slash
            while (result.startsWith("//")) result = result.substring(1);
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the value of a {@code @Path} annotation on a class, if present.
     */
    private static String getPathAnnotationValue(Class<?> clazz) {
        for (Annotation ann : clazz.getAnnotations()) {
            if (ann.annotationType().getName().equals("javax.ws.rs.Path") ||
                    ann.annotationType().getName().equals("jakarta.ws.rs.Path")) {
                try {
                    return (String) ann.annotationType().getMethod("value").invoke(ann);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Finds the JAX-RS method that matches the HTTP method and returns its @Path value.
     * Uses regex template matching ({param} → [^/]+) rather than segment count so that
     * multiple methods with the same number of segments are disambiguated correctly
     * (e.g. /{id}/submit vs /{id}/fail vs /{id}/fail-sync).
     */
    private static String findMatchingMethodPath(Class<?> resourceClass, String httpMethod,
                                                  String requestPath, String classPath) {
        if (httpMethod == null) return null;

        // Compute the remaining path after stripping the class-level @Path prefix.
        String remainingPath = "";
        if (classPath != null && requestPath != null) {
            String cp = classPath.startsWith("/") ? classPath : "/" + classPath;
            String rp = requestPath.startsWith("/") ? requestPath : "/" + requestPath;
            if (rp.startsWith(cp)) {
                remainingPath = rp.substring(cp.length());
            }
        }
        if (!remainingPath.startsWith("/")) remainingPath = "/" + remainingPath;

        String fallback = null;

        for (Method m : resourceClass.getMethods()) {
            boolean hasHttpMethod = false;
            for (Annotation ann : m.getAnnotations()) {
                if (ann.annotationType().getSimpleName().equals(httpMethod)) {
                    hasHttpMethod = true;
                    break;
                }
            }
            if (!hasHttpMethod) continue;

            String methodPath = null;
            for (Annotation ann : m.getAnnotations()) {
                if (ann.annotationType().getName().equals("javax.ws.rs.Path") ||
                        ann.annotationType().getName().equals("jakarta.ws.rs.Path")) {
                    try {
                        methodPath = (String) ann.annotationType().getMethod("value").invoke(ann);
                    } catch (Exception ignored) {}
                    break;
                }
            }

            if (methodPath == null) continue;

            if (templateMatches(methodPath, remainingPath)) {
                return methodPath;
            }
            if (fallback == null) {
                fallback = methodPath;
            }
        }

        return fallback;
    }

    /**
     * Returns true if the JAX-RS path template matches the actual path.
     * Converts {param} and {param:regex} to [^/]+ before matching.
     */
    private static boolean templateMatches(String template, String path) {
        try {
            String t = template.startsWith("/") ? template : "/" + template;
            String p = path.startsWith("/") ? path : "/" + path;
            // {param:custom-regex} and {param} both map to one non-slash segment
            String regex = t.replaceAll("\\{[^}]+}", "[^/]+");
            return Pattern.matches(regex, p);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if the current request is async (CompletionStage/RxJava handler) by
     * checking {@code HttpRequest.getAsyncContext().isSuspended()} via reflection.
     * Returns false on any reflection failure (safe default — treats as sync).
     */
    public static boolean isAsyncRequest(Object requestObj) {
        if (requestObj == null) return false;
        try {
            Object asyncCtx = requestObj.getClass().getMethod("getAsyncContext").invoke(requestObj);
            if (asyncCtx == null) return false;
            // isSuspended() is declared public on ResteasyAsynchronousContext, but the concrete
            // impl (e.g. Servlet3ExecutionContext) is package-private — invoking the method
            // resolved on the impl class throws IllegalAccessException. Resolve it through a
            // public supertype/interface instead.
            Method m = findAccessibleMethod(asyncCtx.getClass(), "isSuspended");
            if (m == null) return false;
            return (boolean) m.invoke(asyncCtx);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Finds a no-arg method by name whose declaring class (or interface) is public, walking
     * up the supertype hierarchy. Needed because reflective invocation requires the declaring
     * type to be accessible, not just the method to be public.
     */
    private static Method findAccessibleMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (java.lang.reflect.Modifier.isPublic(c.getModifiers())) {
                try { return c.getMethod(name); } catch (NoSuchMethodException ignored) {}
            }
            for (Class<?> iface : c.getInterfaces()) {
                if (java.lang.reflect.Modifier.isPublic(iface.getModifiers())) {
                    try { return iface.getMethod(name); } catch (NoSuchMethodException ignored) {}
                }
            }
        }
        return null;
    }

    /**
     * Closes the OTel scope without ending the span. Called from {@link ResteasyDispatchAdvice}
     * when the request is async — the span stays alive and is ended by
     * {@link ResteasyAsyncDeliveryAdvice} when {@code asynchronousDelivery()} completes.
     */
    public static void closeScope() {
        Scope scope = SCOPE_HOLDER.get();
        if (scope != null) {
            scope.close();
            SCOPE_HOLDER.remove();
        }
    }

    /**
     * Stores a throwable on the request so endSpanFromAsync can pick it up when
     * asynchronousDelivery is called from inside writeException (RESTEasy 4.x maps the
     * exception to an HTTP 500 response, then calls asynchronousDelivery to write it).
     * Called from ResteasyWriteExceptionAdvice.onEnter before the inner call happens.
     */
    public static void storeAsyncException(Object requestObj, Throwable throwable) {
        if (throwable != null) setReqAttr(requestObj, ATTR_ASYNC_EXCEPTION, throwable);
    }

    /**
     * Ends the async SERVER span from {@code AsyncResponseConsumer.complete(Throwable)} — the
     * single completion point for both the success and failure paths in the RESTEasy-on-servlet
     * (Undertow) async model, which does NOT route through SynchronousDispatcher.asynchronousDelivery.
     *
     * <p>The HttpRequest/HttpResponse are read reflectively from the consumer's {@code asyncResponse}
     * field ({@code org.jboss.resteasy.core.AbstractAsynchronousResponse}, fields {@code request}/
     * {@code response}). By the time this fires, the response body has been written through the
     * TeeOutputStream, so {@code http.response.body} is available.
     */
    public static void endSpanFromAsyncConsumer(Object consumer, Throwable throwable) {
        if (consumer == null) return;
        try {
            Object asyncResponse = getFieldValue(consumer, "asyncResponse");
            if (asyncResponse == null) return;
            Object requestObj = getFieldValue(asyncResponse, "request");
            Object responseObj = getFieldValue(asyncResponse, "response");
            Span span = (Span) getReqAttr(requestObj, ATTR_SPAN);
            Throwable ex = throwable;
            if (ex == null) ex = (Throwable) getReqAttr(requestObj, ATTR_ASYNC_EXCEPTION);
            endSpan(span, requestObj, responseObj, ex);
        } catch (Exception ignored) {}
    }

    /**
     * Maps an escaped throwable to the HTTP status RESTEasy would send: a WebApplicationException
     * (javax or jakarta) carries its own response status; any other exception maps to 500.
     */
    private static int statusFromThrowable(Throwable t) {
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.equals("javax.ws.rs.WebApplicationException")
                    || name.equals("jakarta.ws.rs.WebApplicationException")) {
                try {
                    Object resp = t.getClass().getMethod("getResponse").invoke(t);
                    if (resp != null) {
                        Object s = resp.getClass().getMethod("getStatus").invoke(resp);
                        int code = (int) s;
                        if (code > 0) return code;
                    }
                } catch (Exception ignored) {}
                break;
            }
        }
        return 500;
    }

    /** Reads a (possibly non-public) field by name, walking up the class hierarchy. */
    private static Object getFieldValue(Object target, String fieldName) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // try superclass
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Ends the span for a delivered async response. Retrieves the span from the request
     * attributes (set by {@link #startSpan}) so it works across async thread hops.
     * If thrown is null but a throwable was stored via storeAsyncException (i.e.
     * asynchronousDelivery was called from inside writeException), uses the stored one.
     */
    public static void endSpanFromAsync(Object requestObj, Object responseObj, Throwable thrown) {
        Span span = (Span) getReqAttr(requestObj, ATTR_SPAN);
        Throwable ex = thrown;
        if (ex == null) {
            ex = (Throwable) getReqAttr(requestObj, ATTR_ASYNC_EXCEPTION);
        }
        endSpan(span, requestObj, responseObj, ex);
    }

    /**
     * Records an exception and ends the span via {@code writeException()}.
     * Works for both sync (exception thrown inside invoke()) and async
     * (CompletionStage that completed exceptionally) paths.
     */
    public static void endSpanFromWriteException(Object requestObj, Object responseObj,
                                                  Throwable throwable) {
        Span span = (Span) getReqAttr(requestObj, ATTR_SPAN);
        endSpan(span, requestObj, responseObj, throwable);
    }

    /**
     * Wraps the RESTEasy HttpResponse output stream with a {@link TeeOutputStream} so that
     * response body bytes are captured for span attachment. The capture buffer is stored on
     * the request attributes (not a ThreadLocal) so it is accessible from async delivery
     * threads via {@link #endSpanFromAsync}.
     *
     * @param requestObj  the RESTEasy HttpRequest (accessed via reflection)
     * @param responseObj the RESTEasy HttpResponse (accessed via reflection)
     */
    public static void captureResponseSetup(Object requestObj, Object responseObj) {
        if (!BodyCaptureConfig.enabled() || !BodyCaptureConfig.captureResponse()) return;
        if (responseObj == null) return;
        // Idempotent: don't re-wrap if already set (called again at asynchronousDelivery enter).
        if (getReqAttr(requestObj, ATTR_RESP_CAPTURE) != null) return;
        try {
            OutputStream original = (OutputStream) responseObj.getClass()
                    .getMethod("getOutputStream").invoke(responseObj);
            if (original == null) return;
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            OutputStream tee = new TeeOutputStream(original, capture, BodyCaptureConfig.maxBytes());
            responseObj.getClass()
                    .getMethod("setOutputStream", OutputStream.class)
                    .invoke(responseObj, tee);
            setReqAttr(requestObj, ATTR_RESP_CAPTURE, capture);
        } catch (Exception ignored) {}
    }

    private static void setReqAttr(Object requestObj, String key, Object value) {
        if (requestObj == null) return;
        try {
            requestObj.getClass()
                    .getMethod("setAttribute", String.class, Object.class)
                    .invoke(requestObj, key, value);
        } catch (Exception ignored) {}
    }

    private static Object getReqAttr(Object requestObj, String key) {
        if (requestObj == null) return null;
        try {
            return requestObj.getClass()
                    .getMethod("getAttribute", String.class)
                    .invoke(requestObj, key);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getResponseContentType(Object responseObj) {
        if (responseObj == null) return null;
        try {
            Object headers = responseObj.getClass().getMethod("getOutputHeaders").invoke(responseObj);
            Object ct = headers.getClass()
                    .getMethod("getFirst", Object.class).invoke(headers, "Content-Type");
            if (ct == null) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> list = (java.util.List<Object>) headers.getClass()
                        .getMethod("get", Object.class).invoke(headers, "Content-Type");
                if (list != null && !list.isEmpty()) ct = list.get(0);
            }
            return ct != null ? ct.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream original;
        private final ByteArrayOutputStream capture;
        private final int maxBytes;
        private int written;

        TeeOutputStream(OutputStream original, ByteArrayOutputStream capture, int maxBytes) {
            this.original = original;
            this.capture = capture;
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            original.write(b);
            if (written < maxBytes) {
                capture.write(b);
                written++;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            original.write(b, off, len);
            if (written < maxBytes) {
                int toCapture = Math.min(len, maxBytes - written);
                capture.write(b, off, toCapture);
                written += toCapture;
            }
        }

        @Override
        public void flush() throws java.io.IOException { original.flush(); }

        @Override
        public void close() throws java.io.IOException { original.close(); }
    }
}
