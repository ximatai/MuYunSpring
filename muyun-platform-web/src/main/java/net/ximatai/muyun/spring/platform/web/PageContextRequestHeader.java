package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parses the explicit, transport-level page-context header used by record scoped operations. */
final class PageContextRequestHeader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private PageContextRequestHeader() {
    }

    static Map<String, Object> parse(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = JSON.readValue(value, MAP);
            if (parsed == null) return Map.of();
            if (parsed.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
                throw invalid();
            }
            return Map.copyOf(parsed);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static RuntimeException invalid() {
        return PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED,
                "Page context header must be a JSON object");
    }
}
