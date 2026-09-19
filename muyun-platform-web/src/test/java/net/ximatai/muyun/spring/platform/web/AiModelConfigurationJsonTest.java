package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.formula.FormulaEngine;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeData;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.ai.AiModelConfiguration;
import net.ximatai.muyun.spring.platform.ai.AiModelConnectionTester;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
                .contains("tenantId", "configurationLevel", "tenantFallbackEnabled", "apiKeyConfigured")
                .doesNotContain("apiKeyInput");
        assertThat(page.detail().editor().fields())
                .extracting(field -> field.fieldRef().fieldName())
                .contains("tenantFallbackEnabled", "apiKeyInput")
                .doesNotContain("apiKeyConfigured");
        assertThat(page.detail().editor().fields())
                .filteredOn(field -> field.fieldRef().fieldName().equals("apiKeyInput"))
                .singleElement()
                .satisfies(field -> assertThat(field.uiType()).isEqualTo("password"));
        assertThat(page.detail().editor().fields())
                .filteredOn(field -> field.fieldRef().fieldName().equals("tenantId"))
                .singleElement()
                .satisfies(field -> assertThat(field.uiType()).isEqualTo("record_picker_dialog"));

        UiFormula fallbackVisibility = page.detail().editor().fields().stream()
                .filter(field -> field.fieldRef().fieldName().equals("tenantFallbackEnabled"))
                .findFirst().orElseThrow().visible().formula();
        var engine = new FormulaEngine();
        assertThat(engine.evaluateBoolean(fallbackVisibility.expression(), FormulaRuntimeData.of(Map.of())))
                .isTrue();
        assertThat(engine.evaluateBoolean(fallbackVisibility.expression(),
                FormulaRuntimeData.of(Map.of("tenantId", "tenant-a"))))
                .isFalse();
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
                .contains(
                        new PageActionDefinition("testConnection", PageActionAnchor.DETAIL,
                                PageActionStatusMode.INPUT_VALIDATION),
                        new PageActionDefinition("testConnection", PageActionAnchor.FORM,
                                PageActionStatusMode.INPUT_VALIDATION));
        assertThat(endpoint).isNotNull();
        assertThat(endpoint.value()).isEqualTo("testConnection");
        assertThat(endpoint.level()).isEqualTo(PlatformActionLevel.RECORD);
        assertThat(endpoint.dataAuth()).isTrue();
        assertThat(endpoint.pageInvocable()).isTrue();
        assertThat(endpoint.formSupported()).isTrue();
    }

    @Test
    void providerCodeRemainsEditableUntilTheRecordHasBeenPersisted() {
        ModuleUiDefinition definition = new AiModelProviderWebController().moduleUiDefinition();
        FlatManagementPageDefinition page = (FlatManagementPageDefinition) definition.page();
        UiFormula readOnly = page.detail().editor().fields().stream()
                .filter(field -> field.fieldRef().fieldName().equals("id"))
                .findFirst().orElseThrow().readOnly().formula();
        var engine = new FormulaEngine();
        for (String draftCode : List.of("", "m", "my_provider")) {
            assertThat(engine.evaluateBoolean(readOnly.expression(),
                    FormulaRuntimeData.of(Map.of("id", draftCode))))
                    .isFalse();
        }
        assertThat(engine.evaluateBoolean(readOnly.expression(),
                FormulaRuntimeData.of(
                        Map.of("id", "my_provider", "version", 0))))
                .isTrue();
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
