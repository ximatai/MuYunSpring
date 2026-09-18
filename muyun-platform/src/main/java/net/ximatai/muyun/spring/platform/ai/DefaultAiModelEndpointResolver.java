package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;

@Service
public class DefaultAiModelEndpointResolver implements AiModelEndpointResolver {
    private final AiModelProviderService providerService;

    public DefaultAiModelEndpointResolver(AiModelProviderService providerService) {
        this.providerService = providerService;
    }

    @Override
    public String resolve(AiModelConfiguration configuration) {
        if (configuration == null || configuration.getProvider() == null || configuration.getProvider().isBlank()) {
            throw new PlatformException("AI model provider is missing");
        }
        return providerService.requireEnabled(configuration.getProvider()).getBaseUrl();
    }
}
