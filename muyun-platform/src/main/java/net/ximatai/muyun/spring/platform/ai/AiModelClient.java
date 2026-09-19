package net.ximatai.muyun.spring.platform.ai;

/** Internal provider adapter boundary. Business code enters through {@link AiModelGateway}. */
interface AiModelClient {
    AiTextResponse generate(ResolvedAiModelRoute route, AiTextRequest request);

    void stream(ResolvedAiModelRoute route, AiTextRequest request, AiTextStreamConsumer consumer);

    AiTurnResponse complete(ResolvedAiModelRoute route, AiTurnRequest request);
}
