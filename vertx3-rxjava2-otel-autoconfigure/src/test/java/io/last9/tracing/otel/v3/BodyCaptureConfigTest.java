package io.last9.tracing.otel.v3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BodyCaptureConfigTest {

    @AfterEach
    void resetEnv() {
        BodyCaptureConfig.envProvider = System::getenv;
    }

    @Test
    void enabledFalseByDefault() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.enabled()).isFalse();
    }

    @Test
    void enabledViaPrimaryEnvVar() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_ENABLED.equals(key) ? "true" : null;
        assertThat(BodyCaptureConfig.enabled()).isTrue();
    }

    @Test
    void enabledViaLegacyEnvVar() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_LEGACY.equals(key) ? "1" : null;
        assertThat(BodyCaptureConfig.enabled()).isTrue();
    }

    @Test
    void enabledImpliedByErrorOnly() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_ERROR_ONLY.equals(key) ? "true" : null;
        assertThat(BodyCaptureConfig.enabled()).isTrue();
    }

    @Test
    void captureRequestDefaultTrue() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.captureRequest()).isTrue();
    }

    @Test
    void captureResponseDefaultTrue() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.captureResponse()).isTrue();
    }

    @Test
    void maxBytesDefault8192() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.maxBytes()).isEqualTo(8192);
    }

    @Test
    void maxBytesCustom() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_MAX_BYTES.equals(key) ? "4096" : null;
        assertThat(BodyCaptureConfig.maxBytes()).isEqualTo(4096);
    }

    @Test
    void errorOnlyDefaultFalse() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.errorOnly()).isFalse();
    }

    @Test
    void isAllowedContentTypeJson() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedContentType("application/json")).isTrue();
        assertThat(BodyCaptureConfig.isAllowedContentType("application/json; charset=utf-8")).isTrue();
    }

    @Test
    void isAllowedContentTypeXml() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedContentType("application/xml")).isTrue();
    }

    @Test
    void isAllowedContentTypeText() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedContentType("text/plain")).isTrue();
        assertThat(BodyCaptureConfig.isAllowedContentType("text/html")).isTrue();
    }

    @Test
    void isAllowedContentTypeRejectsFormData() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedContentType("multipart/form-data")).isFalse();
        assertThat(BodyCaptureConfig.isAllowedContentType("application/octet-stream")).isFalse();
    }

    @Test
    void isAllowedPathNoFilters() {
        BodyCaptureConfig.envProvider = key -> null;
        assertThat(BodyCaptureConfig.isAllowedPath("/api/v1/users")).isTrue();
        assertThat(BodyCaptureConfig.isAllowedPath("/health")).isTrue();
    }

    @Test
    void isAllowedPathExclude() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_EXCLUDE_PATHS.equals(key) ? "/health,/metrics" : null;
        assertThat(BodyCaptureConfig.isAllowedPath("/health")).isFalse();
        assertThat(BodyCaptureConfig.isAllowedPath("/metrics")).isFalse();
        assertThat(BodyCaptureConfig.isAllowedPath("/api/v1/users")).isTrue();
    }

    @Test
    void isAllowedPathInclude() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_INCLUDE_PATHS.equals(key) ? "/api" : null;
        assertThat(BodyCaptureConfig.isAllowedPath("/api/v1/users")).isTrue();
        assertThat(BodyCaptureConfig.isAllowedPath("/health")).isFalse();
    }

    @Test
    void contentTypesCustomList() {
        BodyCaptureConfig.envProvider = key ->
                BodyCaptureConfig.ENV_CONTENT_TYPES.equals(key) ? "text/plain,application/json" : null;
        assertThat(BodyCaptureConfig.isAllowedContentType("text/plain")).isTrue();
        assertThat(BodyCaptureConfig.isAllowedContentType("application/xml")).isFalse();
    }
}
