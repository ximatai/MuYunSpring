package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
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
                .contains("tenantId", "apiKeyConfigured")
                .doesNotContain("apiKeyInput");
        assertThat(page.detail().editor().fields())
                .extracting(field -> field.fieldRef().fieldName())
                .contains("apiKeyInput")
                .doesNotContain("apiKeyConfigured");
        assertThat(page.detail().editor().fields())
                .filteredOn(field -> field.fieldRef().fieldName().equals("apiKeyInput"))
                .singleElement()
                .satisfies(field -> assertThat(field.uiType()).isEqualTo("password"));
        assertThat(page.detail().editor().fields())
                .filteredOn(field -> field.fieldRef().fieldName().equals("tenantId"))
                .singleElement()
                .satisfies(field -> assertThat(field.uiType()).isEqualTo("record_picker_dialog"));
    }

    @Test
    void publishesConnectionTestingThroughTheManagedDetailActionContract() throws Exception {
        ModuleUiDefinition definition = new AiModelConfigurationWebController(org.mockito.Mockito.mock(AiModelConnectionTester.class))
                .moduleUiDefinition();
        CustomActionEndpoint endpoint = AiModelConfigurationWebController.class
                .getMethod("test", String.class)
                .getAnnotation(CustomActionEndpoint.class);

        assertThat(definition.managedActions()).isFalse();
        assertThat(definition.pageActions())
                .contains(new PageActionDefinition("testConnection", PageActionAnchor.DETAIL));
        assertThat(endpoint).isNotNull();
        assertThat(endpoint.value()).isEqualTo("testConnection");
        assertThat(endpoint.level()).isEqualTo(PlatformActionLevel.RECORD);
        assertThat(endpoint.dataAuth()).isTrue();
        assertThat(endpoint.pageInvocable()).isTrue();
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
