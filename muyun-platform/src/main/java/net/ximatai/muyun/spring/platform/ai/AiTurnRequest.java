package net.ximatai.muyun.spring.platform.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Provider-neutral structured model turn. */
public record AiTurnRequest(
        List<AiChatMessage> messages,
        List<AiToolDefinition> tools,
        Double temperature,
        Integer maxOutputTokens
) {
    public AiTurnRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (messages.isEmpty()) throw new IllegalArgumentException("AI turn request requires at least one message");
        Set<String> pending = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (AiChatMessage message : messages) {
            if (message.role() == AiChatMessage.Role.TOOL) {
                if (!pending.remove(message.toolCallId())) {
                    throw new IllegalArgumentException("Orphan or duplicate AI tool result");
                }
            } else {
                if (!pending.isEmpty()) throw new IllegalArgumentException("AI tool results are missing");
                for (AiToolCall call : message.toolCalls()) {
                    if (!seen.add(call.id())) throw new IllegalArgumentException("Duplicate AI tool call id");
                    pending.add(call.id());
                }
            }
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("AI tool results are missing");
        Set<String> codes = new HashSet<>();
        for (AiToolDefinition tool : tools) {
            if (!codes.add(tool.code())) throw new IllegalArgumentException("Duplicate AI tool code: " + tool.code());
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("AI temperature must be between 0 and 2");
        }
        if (maxOutputTokens != null && (maxOutputTokens < 1 || maxOutputTokens > 32_768)) {
            throw new IllegalArgumentException("AI maxOutputTokens must be between 1 and 32768");
        }
    }
}
