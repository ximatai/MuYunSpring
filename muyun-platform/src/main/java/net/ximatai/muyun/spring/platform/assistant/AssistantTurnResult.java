package net.ximatai.muyun.spring.platform.assistant;

import net.ximatai.muyun.spring.platform.ai.AiToolCall;
import net.ximatai.muyun.spring.platform.ai.AiTokenUsage;

import java.util.List;

/** Assistant-layer turn result after platform interactions are separated from page capability calls. */
public record AssistantTurnResult(
        String text,
        List<AiToolCall> toolCalls,
        AssistantSelectionInteraction selection,
        String finishReason,
        String requestId,
        AiTokenUsage usage
) {
    public AssistantTurnResult(String text, List<AiToolCall> toolCalls, AssistantSelectionInteraction selection,
                               String finishReason, String requestId) {
        this(text, toolCalls, selection, finishReason, requestId, null);
    }
    public AssistantTurnResult {
        text = text == null || text.isBlank() ? null : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
