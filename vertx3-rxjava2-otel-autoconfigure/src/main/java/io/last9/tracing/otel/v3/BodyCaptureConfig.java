package io.last9.tracing.otel.v3;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

public final class BodyCaptureConfig {

    public static final String ENV_LEGACY        = "VERTX_OTEL_CAPTURE_HTTP_BODY";
    public static final String ENV_ENABLED       = "VERTX_OTEL_BODY_CAPTURE_ENABLED";
    public static final String ENV_REQUEST       = "VERTX_OTEL_BODY_CAPTURE_REQUEST";
    public static final String ENV_RESPONSE      = "VERTX_OTEL_BODY_CAPTURE_RESPONSE";
    public static final String ENV_MAX_BYTES     = "VERTX_OTEL_BODY_CAPTURE_MAX_BYTES";
    public static final String ENV_ERROR_ONLY    = "VERTX_OTEL_BODY_CAPTURE_ERROR_ONLY";
    public static final String ENV_CONTENT_TYPES = "VERTX_OTEL_BODY_CAPTURE_CONTENT_TYPES";
    public static final String ENV_INCLUDE_PATHS = "VERTX_OTEL_BODY_CAPTURE_INCLUDE_PATHS";
    public static final String ENV_EXCLUDE_PATHS = "VERTX_OTEL_BODY_CAPTURE_EXCLUDE_PATHS";

    private static final int DEFAULT_MAX_BYTES = 8192;
    private static final List<String> DEFAULT_CONTENT_TYPES = Arrays.asList(
            "application/json", "application/xml", "text/");

    // Public for test injection across packages
    public static volatile UnaryOperator<String> envProvider = System::getenv;

    private BodyCaptureConfig() {}

    public static boolean enabled() {
        String v = getenv(ENV_ENABLED);
        if (v == null) v = getenv(ENV_LEGACY);
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    public static boolean captureRequest() {
        return getBool(ENV_REQUEST, true);
    }

    public static boolean captureResponse() {
        return getBool(ENV_RESPONSE, true);
    }

    public static int maxBytes() {
        String v = getenv(ENV_MAX_BYTES);
        if (v == null) return DEFAULT_MAX_BYTES;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_BYTES;
        }
    }

    public static boolean errorOnly() {
        return getBool(ENV_ERROR_ONLY, false);
    }

    public static List<String> contentTypes() {
        String v = getenv(ENV_CONTENT_TYPES);
        if (v == null || v.trim().isEmpty()) return DEFAULT_CONTENT_TYPES;
        return Arrays.asList(v.split(","));
    }

    public static List<String> includePaths() {
        return getCsvList(ENV_INCLUDE_PATHS);
    }

    public static List<String> excludePaths() {
        return getCsvList(ENV_EXCLUDE_PATHS);
    }

    public static boolean isAllowedContentType(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase();
        for (String allowed : contentTypes()) {
            if (ct.startsWith(allowed.trim().toLowerCase())) return true;
        }
        return false;
    }

    public static boolean isAllowedPath(String path) {
        if (path == null) return false;
        for (String excluded : excludePaths()) {
            if (path.startsWith(excluded.trim())) return false;
        }
        List<String> includes = includePaths();
        if (includes.isEmpty()) return true;
        for (String included : includes) {
            if (path.startsWith(included.trim())) return true;
        }
        return false;
    }

    static String getenv(String key) {
        return envProvider.apply(key);
    }

    private static boolean getBool(String envVar, boolean defaultValue) {
        String v = getenv(envVar);
        if (v == null) return defaultValue;
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private static List<String> getCsvList(String envVar) {
        String v = getenv(envVar);
        if (v == null || v.trim().isEmpty()) return Collections.emptyList();
        return Arrays.asList(v.split(","));
    }
}
