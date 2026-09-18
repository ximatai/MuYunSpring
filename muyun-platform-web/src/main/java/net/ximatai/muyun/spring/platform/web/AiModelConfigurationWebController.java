package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.ai.AiModelConfiguration;
import net.ximatai.muyun.spring.platform.ai.AiModelConfigurationService;
import net.ximatai.muyun.spring.platform.ai.AiModelConnectionTestResult;
import net.ximatai.muyun.spring.platform.ai.AiModelConnectionTester;
import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/** Standard management surface for the effective platform or current-tenant model configuration. */
@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@StaticModuleTenantScopePolicy(requireActiveTenant = false)
@PlatformStaticModule(application = PlatformApplication.class, alias = AiModelConfigurationService.MODULE_ALIAS,
        title = "智能模型配置")
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "智能模型配置", order = 30)
@RequestMapping("/platform.ai_model_configuration")
public class AiModelConfigurationWebController
        extends StaticModuleWebControllerAdapter<AiModelConfigurationService>
        implements CrudWeb<AiModelConfiguration, AiModelConfigurationService>,
        ManagedDetailRelationWeb<AiModelConfiguration, AiModelConfigurationService>,
        StaticModuleUiContributor {
    private final AiModelConnectionTester connectionTester;

    public AiModelConfigurationWebController(AiModelConnectionTester connectionTester) {
        this.connectionTester = connectionTester;
    }

    @PostMapping("/{id}/test")
    @ActionEndpoint(PlatformAction.UPDATE)
    public AiModelConnectionTestResult test(@PathVariable String id) {
        return webScope(() -> connectionTester.test(id));
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(AiModelConfigurationService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> {
                    page.explorer(explorer -> explorer.title("智能模型配置").titleField("modelId")
                            .secondaryField("provider"));
                    page.detail(detail -> detail
                            .display(form -> form
                                    .title("智能模型配置")
                                    .field("title", field -> field.label("配置名称").readOnly())
                                    .field("provider", field -> field.label("模型供应商").readOnly())
                                    .field("modelId", field -> field.label("模型 ID").readOnly())
                                    .field("availabilityScope", field -> field.label("适用范围").readOnly())
                                    .field("apiKeyConfigured", field -> field.label("API Key")
                                            .readOnly().booleanStatus("已配置", "未配置")))
                            .editor(form -> form
                                    .title("智能模型配置")
                                    .field("title", field -> field.label("配置名称"))
                                    .field("provider", field -> field.label("模型供应商").required().recordPicker())
                                    .field("modelId", field -> field.label("模型 ID").required())
                                    .field("availabilityScope", field -> field.label("适用范围")
                                            .required().select())
                                    .field("apiKeyInput", field -> field.label("API Key（已配置时留空不修改）")
                                            .required(UiRule.formula(UiFormula.booleanExpression("!(PRESENT({id}))"))))
                                    .field("enabled", field -> field.label("启用状态").enabledStatus())));
                    page.traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()));
                }))
                .relation("tenant_grants", relation -> relation.aggregateChild(child -> child
                        .title("指定租户")
                        .targetEntity("ai_model_configuration_tenant")
                        .parentBinding("configurationId")
                        .visible(UiRule.formula(UiFormula.booleanExpression("{availabilityScope} == 'selectedTenants'")))))
                .build();
    }

    @Override
    public <T> T webScope(Supplier<T> action) {
        return action.get();
    }

}
