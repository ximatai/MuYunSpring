package net.ximatai.muyun.spring.platform.assistant;

/** A bounded browser capability result supplied to the next assistant turn. */
public record AssistantCapabilityResult(String callId, String capabilityCode, java.util.Map<String, Object> input, String execution, Object output,
                                        String errorCode, String errorMessage) {
    public AssistantCapabilityResult {
        if (execution == null || !java.util.Set.of("read", "effect-applied", "not-applied", "unknown").contains(execution)) {
            throw new IllegalArgumentException("assistant capability execution is invalid");
        }
        if (input == null) throw new IllegalArgumentException("assistant capability result input is required");
        input = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(input));
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("assistant capability result callId must not be blank");
        }
        if (capabilityCode == null || capabilityCode.isBlank()) {
            throw new IllegalArgumentException("assistant capability result capabilityCode must not be blank");
        }
    }
}
