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
