package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.ai.AiModelConfiguration;
import net.ximatai.muyun.spring.platform.ai.AiModelConfigurationService;
import net.ximatai.muyun.spring.platform.ai.AiModelConnectionTestResult;
import net.ximatai.muyun.spring.platform.ai.AiModelConnectionTester;
import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.web.NestedCrudWebSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/** Standard management surface for the effective platform or current-tenant model configuration. */
@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = PlatformApplication.class, alias = AiModelConfigurationService.MODULE_ALIAS,
        title = "智能模型配置")
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "智能模型配置", order = 30)
@RequestMapping("/platform.ai_model_configuration")
public class AiModelConfigurationWebController
        extends NestedCrudWebSupport<AiModelConfiguration, AiModelConfigurationService>
        implements StaticModuleUiContributor {
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
                    page.detail(detail -> detail.editor(form -> form
                            .title("智能模型配置")
                            .field("provider", field -> field.label("模型供应商").required().select())
                            .field("modelId", field -> field.label("模型 ID").required())
                            .field("apiKeyInput", field -> field.label("API Key").required())
                            .field("enabled", field -> field.label("启用状态").enabledStatus())));
                    page.traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()));
                }))
                .build();
    }

    @Override
    public <T> T webScope(Supplier<T> action) {
        return action.get();
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
    }

    @Override
    protected void bindScope(AiModelConfiguration record, HttpServletRequest request) {
    }

    @Override
    protected boolean inScope(AiModelConfiguration record, HttpServletRequest request) {
        return true;
    }
}
