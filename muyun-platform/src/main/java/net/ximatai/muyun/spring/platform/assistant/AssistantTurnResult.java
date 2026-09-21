package net.ximatai.muyun.spring.platform.assistant;

import net.ximatai.muyun.spring.platform.ai.AiToolCall;

import java.util.List;

/** Assistant-layer turn result after platform interactions are separated from page capability calls. */
public record AssistantTurnResult(
        String text,
        List<AiToolCall> toolCalls,
        AssistantSelectionInteraction selection,
        String finishReason,
        String requestId
) {
    public AssistantTurnResult {
        text = text == null || text.isBlank() ? null : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
