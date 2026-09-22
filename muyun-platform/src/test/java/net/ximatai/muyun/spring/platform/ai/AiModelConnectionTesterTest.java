package net.ximatai.muyun.spring.platform.ai;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelConnectionTesterTest {
    @Test
    void testsAnExistingDraftWithItsStoredCredentialWhenTheEditorLeavesItBlank() {
        AiModelConfigurationService configurations = mock(AiModelConfigurationService.class);
        AiModelRouteResolver routes = passthroughRouteResolver();
        AiModelClient client = mock(AiModelClient.class);
        AiModelConfiguration stored = new AiModelConfiguration();
        stored.setApiKey("stored-secret");
        when(configurations.select("config-1")).thenReturn(stored);

        AiModelConfiguration draft = new AiModelConfiguration();
        draft.setId("config-1");
        draft.setProvider(" lm_studio ");
        draft.setModelId(" local-model ");

        AiModelConnectionTestResult result = new AiModelConnectionTester(configurations, routes, client).testDraft(draft);

        assertThat(result.connected()).isTrue();
        assertThat(result.message()).isEqualTo("模型连接验证通过");
        ArgumentCaptor<ResolvedAiModelRoute> candidate = ArgumentCaptor.forClass(ResolvedAiModelRoute.class);
        verify(client).generate(candidate.capture(), any(AiTextRequest.class));
        assertThat(candidate.getValue().provider()).isEqualTo("lm_studio");
        assertThat(candidate.getValue().modelId()).isEqualTo("local-model");
        assertThat(candidate.getValue().apiKey()).isEqualTo("stored-secret");
    }

    @Test
    void testsANewDraftWithItsUnsavedCredential() {
        AiModelConfigurationService configurations = mock(AiModelConfigurationService.class);
        AiModelRouteResolver routes = passthroughRouteResolver();
        AiModelClient client = mock(AiModelClient.class);
        AiModelConfiguration draft = new AiModelConfiguration();
        draft.setProvider("deepseek");
        draft.setModelId("deepseek-chat");
        draft.setApiKeyInput("draft-secret");

        new AiModelConnectionTester(configurations, routes, client).testDraft(draft);

        ArgumentCaptor<ResolvedAiModelRoute> candidate = ArgumentCaptor.forClass(ResolvedAiModelRoute.class);
        verify(client).generate(candidate.capture(), any(AiTextRequest.class));
        assertThat(candidate.getValue().apiKey()).isEqualTo("draft-secret");
    }

    @Test
    void rejectsADraftWithoutAnAvailableCredential() {
        AiModelConnectionTester tester = new AiModelConnectionTester(
                mock(AiModelConfigurationService.class), passthroughRouteResolver(), mock(AiModelClient.class));
        AiModelConfiguration draft = new AiModelConfiguration();
        draft.setProvider("deepseek");
        draft.setModelId("deepseek-chat");

        assertThatThrownBy(() -> tester.testDraft(draft))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                .hasMessage("AI model API key must be provided before testing");
    }

    @Test
    void environmentDraftUsesTheSameRuntimeResolverWithoutPersistingTheSecret() {
        AiModelConfigurationService configurations = mock(AiModelConfigurationService.class);
        AiModelProviderService providers = mock(AiModelProviderService.class);
        AiModelProvider provider = new AiModelProvider();
        provider.setId("test");
        provider.setProtocol(AiModelProtocol.OPENAI_COMPATIBLE);
        provider.setBaseUrl("https://example.test/v1");
        when(providers.requireEnabled("test")).thenReturn(provider);
        AiModelRouteResolver routes = new DefaultAiModelRouteResolver(configurations, providers,
                new AiModelCredentialResolver(name -> "environment-secret"));
        AiModelClient client = mock(AiModelClient.class);
        AiModelConnectionTester tester = new AiModelConnectionTester(configurations, routes, client);
        AiModelConfiguration draft = new AiModelConfiguration();
        draft.setProvider("test");
        draft.setModelId("test-model");
        draft.setCredentialSource(AiModelCredentialSource.ENVIRONMENT);
        draft.setApiKeyEnvironmentVariable("MUYUN_AI_TEST_KEY");
        assertThatThrownBy(() -> tester.testDraft(draft)).hasMessageContaining("只有平台管理员");
        try (var user = net.ximatai.muyun.spring.common.identity.CurrentUserContext.use(
                net.ximatai.muyun.spring.common.identity.CurrentUser.systemUser("root", "root"))) {
            assertThat(tester.testDraft(draft).connected()).isTrue();
        }
        ArgumentCaptor<ResolvedAiModelRoute> route = ArgumentCaptor.forClass(ResolvedAiModelRoute.class);
        verify(client).generate(route.capture(), any(AiTextRequest.class));
        assertThat(route.getValue().apiKey()).isEqualTo("environment-secret");
        assertThat(draft.getApiKey()).isNull();
        org.mockito.Mockito.verifyNoInteractions(configurations);
    }

    private AiModelRouteResolver passthroughRouteResolver() {
        return new AiModelRouteResolver() {
            @Override
            public ResolvedAiModelRoute resolveCurrent() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ResolvedAiModelRoute resolveCandidate(AiModelConfiguration configuration) {
                return new ResolvedAiModelRoute(configuration.getProvider(), AiModelProtocol.OPENAI_COMPATIBLE,
                        "https://example.test/v1", configuration.getModelId(), configuration.getApiKey());
            }
        };
    }
}
