package net.ximatai.muyun.spring.platform.ai;

/** Normalized text completion response without provider-specific transport objects. */
public record AiTextResponse(String text, String finishReason, String providerRequestId) {
}
