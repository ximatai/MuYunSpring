package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;

@Service
public class DefaultAiModelEndpointResolver implements AiModelEndpointResolver {
    @Override
    public String resolve(AiModelConfiguration configuration) {
        if (configuration == null || configuration.getProvider() == null) {
            throw new PlatformException("AI model provider is missing");
        }
        return configuration.getProvider().baseUrl();
    }
}
