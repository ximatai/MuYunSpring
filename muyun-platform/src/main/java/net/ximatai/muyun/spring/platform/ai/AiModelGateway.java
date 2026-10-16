package net.ximatai.muyun.spring.platform.ai;

/** Application-facing entry point which always resolves the active platform/tenant scope. */
public interface AiModelGateway {
    /**
     * Whether the current trusted identity has a resolvable route, enabled provider and
     * usable credentials. Does not call the provider or reserve generation capacity.
     * This is a point-in-time configuration check, not a promise of provider health;
     * generation must still resolve the route again. Unknown implementations fail closed.
     */
    default boolean isTextGenerationAvailable() { return false; }

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
