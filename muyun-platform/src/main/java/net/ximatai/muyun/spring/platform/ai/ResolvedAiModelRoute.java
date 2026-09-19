package net.ximatai.muyun.spring.platform.ai;

/** Immutable runtime connection selected for one model invocation. */
record ResolvedAiModelRoute(
        String provider,
        AiModelProtocol protocol,
        String baseUrl,
        String modelId,
        String apiKey
) {
    ResolvedAiModelRoute {
        provider = requireText(provider, "provider");
        if (protocol == null) throw new IllegalArgumentException("protocol must not be null");
        baseUrl = requireText(baseUrl, "baseUrl");
        modelId = requireText(modelId, "modelId");
        apiKey = requireText(apiKey, "apiKey");
    }

    String chatCompletionsUrl() {
        return baseUrl + "/chat/completions";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
