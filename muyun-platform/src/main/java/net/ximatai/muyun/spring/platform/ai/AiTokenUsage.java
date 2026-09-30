package net.ximatai.muyun.spring.platform.ai;

/** Provider-reported counts; absent usage is unknown, not zero. */
public record AiTokenUsage(Long inputTokens, Long outputTokens, Long totalTokens) {
    public AiTokenUsage {
        if ((inputTokens != null && inputTokens < 0) || (outputTokens != null && outputTokens < 0)
                || (totalTokens != null && totalTokens < 0)) {
            throw new IllegalArgumentException("AI token usage must be non-negative");
        }
    }
}
