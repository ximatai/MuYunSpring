package net.ximatai.muyun.spring.platform.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeAuditPayloadSanitizerTest {
    @Test
    void redactsApiKeysAndAuthorizationHeadersIncludingNestedPayloads() {
        RuntimeAuditPayloadSanitizer sanitizer = new RuntimeAuditPayloadSanitizer();

        Map<String, Object> sanitized = sanitizer.sanitize(Map.of(
                "apiKey", "model-secret",
                "nested", Map.of("Authorization", "Bearer model-secret"),
                "modelId", "deepseek-chat"));

        assertThat(sanitized).containsEntry("apiKey", RuntimeAuditPayloadSanitizer.REDACTED)
                .containsEntry("modelId", "deepseek-chat");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) sanitized.get("nested");
        assertThat(nested)
                .containsEntry("Authorization", RuntimeAuditPayloadSanitizer.REDACTED);
    }
}
