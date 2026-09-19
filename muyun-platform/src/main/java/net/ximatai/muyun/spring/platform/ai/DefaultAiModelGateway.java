package net.ximatai.muyun.spring.platform.ai;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
final class DefaultAiModelGateway implements AiModelGateway {
    private final AiModelRouteResolver routeResolver;
    private final AiModelClient client;

    DefaultAiModelGateway(AiModelRouteResolver routeResolver, AiModelClient client) {
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public AiTextResponse generate(AiTextRequest request) {
        return client.generate(routeResolver.resolveCurrent(), request);
    }

    @Override
    public void stream(AiTextRequest request, AiTextStreamConsumer consumer) {
        client.stream(routeResolver.resolveCurrent(), request, consumer);
    }

    @Override
    public AiTurnResponse complete(AiTurnRequest request) {
        return client.complete(routeResolver.resolveCurrent(), request);
    }
}
