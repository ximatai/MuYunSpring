package net.ximatai.muyun.spring.platform.ai;

/** A provider-neutral text conversation item. */
public record AiChatMessage(Role role, String content) {
    public enum Role { SYSTEM, USER, ASSISTANT }

    public AiChatMessage {
        if (role == null) throw new IllegalArgumentException("AI message role must not be null");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("AI message content must not be blank");
    }
}
