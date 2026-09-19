package net.ximatai.muyun.spring.platform.assistant;

/** A bounded user-visible dialogue item carried by the browser between assistant turns. */
public record AssistantConversationMessage(Role role, String text) {
    public enum Role { USER, ASSISTANT }

    public AssistantConversationMessage {
        if (role == null) throw new IllegalArgumentException("assistant history role must not be null");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("assistant history text must not be blank");
        }
    }
}
