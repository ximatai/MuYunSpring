package net.ximatai.muyun.spring.platform.ai;

/** Normalized text completion response without provider-specific transport objects. */
public record AiTextResponse(String text, String finishReason, String providerRequestId, AiTokenUsage usage) {
    public AiTextResponse(String text, String finishReason, String providerRequestId) {
        this(text, finishReason, providerRequestId, null);
    }
}
