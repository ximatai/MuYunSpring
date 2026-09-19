package net.ximatai.muyun.spring.platform.ai;

/** Application-facing entry point which always resolves the active platform/tenant scope. */
public interface AiModelGateway {
    AiTextResponse generate(AiTextRequest request);

    void stream(AiTextRequest request, AiTextStreamConsumer consumer);

    /** Completes one provider-neutral structured turn without executing returned tool calls. */
    AiTurnResponse complete(AiTurnRequest request);
}
