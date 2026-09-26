package net.ximatai.muyun.spring.platform.ai;

import java.util.List;

/** Provider-neutral text and paired tool messages. Tool results remain untrusted data. */
public record AiChatMessage(Role role, String content, List<AiToolCall> toolCalls, String toolCallId) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public AiChatMessage {
        if (role == null) throw new IllegalArgumentException("AI message role must not be null");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (!toolCalls.isEmpty() && role != Role.ASSISTANT) {
            throw new IllegalArgumentException("Only assistant messages can request tools");
        }
        if (role == Role.TOOL ? toolCallId == null || toolCallId.isBlank() : toolCallId != null) {
            throw new IllegalArgumentException("Tool result requires a call id; other roles cannot carry one");
        }
        if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("AI message must contain text or tool calls");
        }
    }

    public AiChatMessage(Role role, String content) {
        this(role, content, List.of(), null);
    }

    public static AiChatMessage call(AiToolCall call) {
        return new AiChatMessage(Role.ASSISTANT, null, List.of(call), null);
    }

    public static AiChatMessage result(String callId, String content) {
        return new AiChatMessage(Role.TOOL, content, List.of(), callId);
    }
}
