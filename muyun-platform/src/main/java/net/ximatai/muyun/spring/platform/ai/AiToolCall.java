package net.ximatai.muyun.spring.platform.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A structured model request to invoke one declared tool. */
public record AiToolCall(String id, String code, Map<String, Object> arguments) {
    public AiToolCall {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("AI tool call id must not be blank");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("AI tool call code must not be blank");
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
