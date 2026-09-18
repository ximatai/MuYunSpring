package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ai.AiModelProvider;
import net.ximatai.muyun.spring.platform.ai.AiModelProviderService;
import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Super-administrator catalogue for the providers selectable by model configurations. */
@RestController
@PlatformStaticModule(application = PlatformApplication.class, alias = AiModelProviderService.MODULE_ALIAS,
        title = "模型供应商")
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "模型供应商", order = 20)
@RequestMapping("/platform.ai_model_provider")
public class AiModelProviderWebController extends WebSupport<AiModelProviderService> implements
        CrudWeb<AiModelProvider, AiModelProviderService>,
        SystemScope<AiModelProviderService>,
        StaticModuleUiContributor {
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(AiModelProviderService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("模型供应商").titleField("title").secondaryField("id"))
                        .detail(detail -> detail.editor(form -> form.title("模型供应商")
                                .field("id", field -> field.label("供应商编码").required()
                                        .enabledWhen(UiFormula.booleanExpression("!(PRESENT({version}))")))
                                .field("title", field -> field.label("供应商名称").required())
                                .field("protocol", field -> field.label("接口协议").required().select())
                                .field("baseUrl", field -> field.label("API 基础地址").required())
                                .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()))))
                .build();
    }
}
