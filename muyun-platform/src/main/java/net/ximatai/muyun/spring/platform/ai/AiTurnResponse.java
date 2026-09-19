package net.ximatai.muyun.spring.platform.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Text and structured tool calls returned by one model turn. */
public record AiTurnResponse(String text, List<AiToolCall> toolCalls, String finishReason, String requestId) {
    public AiTurnResponse {
        text = text == null || text.isBlank() ? null : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        Set<String> callIds = new HashSet<>();
        for (AiToolCall call : toolCalls) {
            if (!callIds.add(call.id())) {
                throw new IllegalArgumentException("Duplicate AI tool call id: " + call.id());
            }
        }
        if (text == null && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("AI turn response requires text or tool calls");
        }
    }
}
