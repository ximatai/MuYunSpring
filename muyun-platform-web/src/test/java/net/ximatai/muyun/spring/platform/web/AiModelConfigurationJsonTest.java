package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.platform.ai.AiModelConfiguration;
import net.ximatai.muyun.spring.platform.ai.AiModelConnectionTester;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelConfigurationJsonTest {
    @Test
    void keepsThePlatformWorkspaceAvailableWithoutSelectingATenant() {
        StaticModuleTenantScopePolicy policy = AiModelConfigurationWebController.class
                .getAnnotation(StaticModuleTenantScopePolicy.class);

        assertThat(policy).isNotNull();
        assertThat(policy.requireActiveTenant()).isFalse();
    }

    @Test
    void detailShowsTheCredentialStatusButNeverTheWriteOnlyCredentialInput() {
        ModuleUiDefinition definition = new AiModelConfigurationWebController(org.mockito.Mockito.mock(AiModelConnectionTester.class))
                .moduleUiDefinition();
        FlatManagementPageDefinition page = (FlatManagementPageDefinition) definition.page();

        assertThat(page.detail().display().fields())
                .extracting(field -> field.fieldRef().fieldName())
                .contains("apiKeyConfigured")
                .doesNotContain("apiKeyInput");
        assertThat(page.detail().editor().fields())
                .extracting(field -> field.fieldRef().fieldName())
                .contains("apiKeyInput")
                .doesNotContain("apiKeyConfigured");
    }

    @Test
    void acceptsWriteOnlyKeyButNeverSerializesCredentialFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AiModelConfiguration input = mapper.readValue("""
                {"provider":"lm_studio","modelId":"local-model","apiKeyInput":"model-secret"}
                """, AiModelConfiguration.class);
        input.setApiKey("plain-never-returned");
        input.setApiKeySignature("signature-never-returned");
        input.setApiKeyConfigured(Boolean.TRUE);

        String output = mapper.writeValueAsString(input);

        assertThat(input.getApiKeyInput()).isEqualTo("model-secret");
        assertThat(output).contains("\"apiKeyConfigured\":true")
                .doesNotContain("apiKeyInput", "plain-never-returned", "signature-never-returned", "model-secret");
    }
}
