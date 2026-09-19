package net.ximatai.muyun.spring.platform.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provider-neutral model tool declaration. Business execution remains outside the model gateway. */
public record AiToolDefinition(String code, String description, Map<String, Object> inputSchema) {
    public AiToolDefinition {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("AI tool code must not be blank");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("AI tool description must not be blank");
        }
        inputSchema = inputSchema == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }
}
