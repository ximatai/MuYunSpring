package net.ximatai.muyun.spring.platform.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RuntimeAuditPayloadSanitizer {
    public static final String REDACTED = "[REDACTED]";

    public Map<String, Object> sanitize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        payload.forEach((key, value) -> sanitized.put(key, sanitizeValue(key, value)));
        return Map.copyOf(sanitized);
    }

    public String sanitizeText(String fieldName, String value) {
        if (value == null) {
            return null;
        }
        return sensitiveKey(fieldName) ? REDACTED : value;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (sensitiveKey(key)) {
            return REDACTED;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> sanitized.put(
                    String.valueOf(nestedKey),
                    sanitizeValue(String.valueOf(nestedKey), nestedValue)
            ));
            return Map.copyOf(sanitized);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> sanitizeValue(key, item)).toList();
        }
        return value;
    }

    private boolean sensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("signature");
    }
}
