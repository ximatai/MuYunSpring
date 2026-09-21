package net.ximatai.muyun.spring.platform.assistant;

import net.ximatai.muyun.spring.platform.ai.AiToolDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Browser-projected facts for one assistant turn; identity and model routing are never client supplied. */
public record AssistantTurnCommand(
        String message,
        List<AssistantConversationMessage> history,
        Map<String, Object> context,
        List<AiToolDefinition> capabilities,
        List<AssistantCapabilityResult> results,
        AssistantSelectionResponse selectionResponse
) {
    public AssistantTurnCommand {
        if (history != null && history.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("assistant history item must not be null");
        }
        history = history == null ? List.of() : List.copyOf(history);
        context = context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context));
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        results = results == null ? List.of() : List.copyOf(results);
    }

    public AssistantTurnCommand(String message, Map<String, Object> context,
                                List<AiToolDefinition> capabilities,
                                List<AssistantCapabilityResult> results) {
        this(message, List.of(), context, capabilities, results, null);
    }

    public AssistantTurnCommand(String message, List<AssistantConversationMessage> history,
                                Map<String, Object> context, List<AiToolDefinition> capabilities,
                                List<AssistantCapabilityResult> results) {
        this(message, history, context, capabilities, results, null);
    }
}
