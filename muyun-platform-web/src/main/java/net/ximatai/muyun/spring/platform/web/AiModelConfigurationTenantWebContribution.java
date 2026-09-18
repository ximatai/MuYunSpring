package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ai.AiModelConfigurationService;
import net.ximatai.muyun.spring.platform.ai.AiModelConfigurationTenantService;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.RestController;

/** Supplies the selected-tenant child model facts to the model-configuration aggregate editor. */
@RestController
@PlatformStaticActionContribution(targetModule = AiModelConfigurationService.MODULE_ALIAS,
        resource = "ai_model_configuration_tenant", resourceTitle = "指定租户")
public class AiModelConfigurationTenantWebContribution extends WebSupport<AiModelConfigurationTenantService>
        implements StaticModuleUiContributor {
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(AiModelConfigurationService.MODULE_ALIAS)
                .editorContribution("ai_model_configuration_tenant", form -> form.title("指定租户")
                        .field("ai_model_configuration_tenant", "targetTenantId", field -> field.label("租户")
                                .required().recordPicker()))
                .build();
    }
}
