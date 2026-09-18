package net.ximatai.muyun.spring.platform.ai;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class DefaultAiModelGateway implements AiModelGateway {
    private final AiModelConfigurationService configurationService;
    private final AiModelClient client;

    public DefaultAiModelGateway(AiModelConfigurationService configurationService, AiModelClient client) {
        this.configurationService = Objects.requireNonNull(configurationService, "configurationService must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public AiTextResponse generate(AiTextRequest request) {
        return client.generate(configurationService.requireEffectiveConfiguration(), request);
    }

    @Override
    public void stream(AiTextRequest request, AiTextStreamConsumer consumer) {
        client.stream(configurationService.requireEffectiveConfiguration(), request, consumer);
    }
}
