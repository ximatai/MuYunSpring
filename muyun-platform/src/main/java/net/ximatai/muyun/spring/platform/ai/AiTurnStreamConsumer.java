package net.ximatai.muyun.spring.platform.ai;

/** Receives provider-neutral structured turn deltas and the validated terminal response. */
public interface AiTurnStreamConsumer {
    void onTextDelta(String text);

    void onComplete(AiTurnResponse response);
}
