package net.ximatai.muyun.spring.platform.assistant;

import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Browser-projected facts for one assistant turn; identity and model routing are never client supplied. */
public record AssistantTurnCommand(
        String message,
        Map<String, Object> context,
        List<AiToolDefinition> capabilities,
        List<AssistantCapabilityResult> results
) {
    public AssistantTurnCommand {
        context = context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context));
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        results = results == null ? List.of() : List.copyOf(results);
    }
}
