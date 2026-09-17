package net.ximatai.muyun.spring.platform.ai;

import java.util.List;

/** The stable first-stage text generation contract. */
public record AiTextRequest(List<AiChatMessage> messages, Double temperature, Integer maxOutputTokens) {
    public AiTextRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) throw new IllegalArgumentException("AI text request requires at least one message");
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("AI temperature must be between 0 and 2");
        }
        if (maxOutputTokens != null && (maxOutputTokens < 1 || maxOutputTokens > 32_768)) {
            throw new IllegalArgumentException("AI maxOutputTokens must be between 1 and 32768");
        }
    }

    public static AiTextRequest userText(String text) {
        return new AiTextRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, text)), null, null);
    }
}
