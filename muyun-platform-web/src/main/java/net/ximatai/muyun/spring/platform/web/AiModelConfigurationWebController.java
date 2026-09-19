package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.ai.AiModelConfiguration;
import net.ximatai.muyun.spring.platform.ai.AiModelConfigurationService;
import net.ximatai.muyun.spring.platform.ai.AiModelConnectionTestResult;
import net.ximatai.muyun.spring.platform.ai.AiModelConnectionTester;
import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.FormActionRequest;
import net.ximatai.muyun.spring.web.FormActionWeb;
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
        FormActionWeb<AiModelConfigurationService, AiModelConfiguration, AiModelConnectionTestResult>,
        StaticModuleUiContributor {
    private static final String TEST_CONNECTION_ACTION = "testConnection";

    private final AiModelConnectionTester connectionTester;

    public AiModelConfigurationWebController(AiModelConnectionTester connectionTester) {
        this.connectionTester = connectionTester;
    }

    @PostMapping("/{id}/test")
    @CustomActionEndpoint(value = TEST_CONNECTION_ACTION, title = "测试连接", level = PlatformActionLevel.RECORD,
            dataAuth = true, pageInvocable = true, formSupported = true)
    public AiModelConnectionTestResult test(@PathVariable String id) {
        return webScope(() -> connectionTester.test(id));
    }

    @Override
    public AiModelConnectionTestResult executeFormAction(String actionCode,
                                                          FormActionRequest<AiModelConfiguration> request) {
        if (!TEST_CONNECTION_ACTION.equals(actionCode)) {
            throw new IllegalArgumentException("unsupported AI model form action: " + actionCode);
        }
        return connectionTester.testDraft(request.record());
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
                                    .field("configurationLevel", field -> field.label("配置级别").readOnly())
                                    .field("tenantFallbackEnabled", field -> field.label("面向租户共享")
                                            .visible(UiRule.formula(UiFormula.booleanExpression("!(PRESENT({tenantId}))")))
                                            .readOnly().booleanStatus("已开放", "仅平台使用"))
                                    .field("tenantId", field -> field.label("绑定租户（平台级无需绑定）").readOnly())
                                    .field("apiKeyConfigured", field -> field.label("API Key")
                                            .readOnly().booleanStatus("已配置", "未配置")))
                            .editor(form -> form
                                    .title("智能模型配置")
                                    .field("title", field -> field.label("配置名称"))
                                    .field("provider", field -> field.label("模型供应商").required().recordPicker())
                                    .field("modelId", field -> field.label("模型 ID").required())
                                    .field("tenantId", field -> field.label("绑定租户（留空为平台级）")
                                            .recordPickerDialog())
                                    .field("tenantFallbackEnabled", field -> field.label("面向租户共享")
                                            .visible(UiRule.formula(UiFormula.booleanExpression("!(PRESENT({tenantId}))"))))
                                    .field("apiKeyInput", field -> field.label("API Key（已配置时留空不修改）")
                                            .secretInput()
                                            .required(UiRule.formula(UiFormula.booleanExpression("!(PRESENT({id}))"))))
                                    .field("enabled", field -> field.label("启用状态").enabledStatus())));
                    page.traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()));
                }))
                .pageAction(TEST_CONNECTION_ACTION, PageActionAnchor.DETAIL, PageActionStatusMode.INPUT_VALIDATION)
                .pageAction(TEST_CONNECTION_ACTION, PageActionAnchor.FORM, PageActionStatusMode.INPUT_VALIDATION)
                .build();
    }

    @Override
    public <T> T webScope(Supplier<T> action) {
        return action.get();
    }

}
