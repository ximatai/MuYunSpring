package net.ximatai.muyun.spring.platform.assistant;

/** Streaming delivery boundary for assistant-visible text and the validated terminal result. */
public interface AssistantTurnStreamConsumer {
    default void onTextDelta(String text) {
    }

    void onComplete(AssistantTurnResult result);
}
