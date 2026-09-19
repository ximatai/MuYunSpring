package net.ximatai.muyun.spring.platform.ai;

/** Application-facing entry point which always resolves the active platform/tenant scope. */
public interface AiModelGateway {
    AiTextResponse generate(AiTextRequest request);

    void stream(AiTextRequest request, AiTextStreamConsumer consumer);

    /** Completes one provider-neutral structured turn without executing returned tool calls. */
    AiTurnResponse complete(AiTurnRequest request);

    /** Streams visible text while retaining one validated terminal structured turn. */
    default void stream(AiTurnRequest request, AiTurnStreamConsumer consumer) {
        AiTurnResponse response = complete(request);
        if (response.text() != null && !response.text().isEmpty()) consumer.onTextDelta(response.text());
        consumer.onComplete(response);
    }
}
