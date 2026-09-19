package net.ximatai.muyun.spring.platform.assistant;

/** A bounded browser capability result supplied to the next assistant turn. */
public record AssistantCapabilityResult(String callId, Object output, String errorCode, String errorMessage) {
    public AssistantCapabilityResult {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("assistant capability result callId must not be blank");
        }
    }
}
