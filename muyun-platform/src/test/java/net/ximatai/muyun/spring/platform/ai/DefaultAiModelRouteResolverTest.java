package net.ximatai.muyun.spring.platform.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAiModelRouteResolverTest {
    @Test
    void resolvesTheCurrentConfigurationAndProviderIntoATransportSnapshot() {
        AiModelConfigurationService configurations = mock(AiModelConfigurationService.class);
        AiModelProviderService providers = mock(AiModelProviderService.class);
        AiModelConfiguration configuration = new AiModelConfiguration();
        configuration.setProvider("lm_studio");
        configuration.setModelId("local-model");
        configuration.setApiKey("model-secret");
        AiModelProvider provider = new AiModelProvider();
        provider.setId("lm_studio");
        provider.setProtocol(AiModelProtocol.OPENAI_COMPATIBLE);
        provider.setBaseUrl("http://127.0.0.1:1234/v1");
        when(configurations.requireEffectiveConfiguration()).thenReturn(configuration);
        when(providers.requireEnabled("lm_studio")).thenReturn(provider);

        ResolvedAiModelRoute route = new DefaultAiModelRouteResolver(configurations, providers, new AiModelCredentialResolver()).resolveCurrent();

        assertThat(route.provider()).isEqualTo("lm_studio");
        assertThat(route.protocol()).isEqualTo(AiModelProtocol.OPENAI_COMPATIBLE);
        assertThat(route.chatCompletionsUrl()).isEqualTo("http://127.0.0.1:1234/v1/chat/completions");
        assertThat(route.modelId()).isEqualTo("local-model");
        assertThat(route.apiKey()).isEqualTo("model-secret");
    }
}
