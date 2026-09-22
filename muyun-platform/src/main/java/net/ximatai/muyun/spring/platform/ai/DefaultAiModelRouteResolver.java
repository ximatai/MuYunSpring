package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
final class DefaultAiModelRouteResolver implements AiModelRouteResolver {
    private final AiModelConfigurationService configurationService;
    private final AiModelProviderService providerService;
    private final AiModelCredentialResolver credentialResolver;

    DefaultAiModelRouteResolver(AiModelConfigurationService configurationService,
                                AiModelProviderService providerService, AiModelCredentialResolver credentialResolver) {
        this.configurationService = Objects.requireNonNull(configurationService,
                "configurationService must not be null");
        this.credentialResolver = Objects.requireNonNull(credentialResolver);
        this.providerService = Objects.requireNonNull(providerService, "providerService must not be null");
    }

    @Override
    public ResolvedAiModelRoute resolveCurrent() {
        return resolveCandidate(configurationService.requireEffectiveConfiguration());
    }

    @Override
    public ResolvedAiModelRoute resolveCandidate(AiModelConfiguration configuration) {
        if (configuration == null || configuration.getProvider() == null || configuration.getProvider().isBlank()) {
            throw new PlatformException("AI model provider is missing");
        }
        AiModelProvider provider = providerService.requireEnabled(configuration.getProvider());
        return new ResolvedAiModelRoute(provider.getId(), provider.getProtocol(), provider.getBaseUrl(),
                configuration.getModelId(), credentialResolver.resolve(configuration));
    }
}
