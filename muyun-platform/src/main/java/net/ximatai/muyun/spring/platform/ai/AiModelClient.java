package net.ximatai.muyun.spring.platform.ai;

/** Provider adapter boundary. Agent and business code never depend on a vendor SDK. */
public interface AiModelClient {
    AiTextResponse generate(AiModelConfiguration configuration, AiTextRequest request);

    void stream(AiModelConfiguration configuration, AiTextRequest request, AiTextStreamConsumer consumer);
}
