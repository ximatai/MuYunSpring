package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ai.AiModelPlatformSetting;
import net.ximatai.muyun.spring.platform.ai.AiModelPlatformSettingService;
import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.web.NestedCrudWebSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Global governance switch for tenant-owned model connections. */
@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = PlatformApplication.class, alias = AiModelPlatformSettingService.MODULE_ALIAS,
        title = "智能模型平台设置")
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "智能模型平台设置", order = 20)
@RequestMapping("/platform.ai_model_setting")
public class AiModelPlatformSettingWebController
        extends NestedCrudWebSupport<AiModelPlatformSetting, AiModelPlatformSettingService>
        implements StaticModuleUiContributor {
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(AiModelPlatformSettingService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> {
                    page.explorer(explorer -> explorer.title("智能模型平台设置")
                            .titleField("tenantRegistrationEnabled"));
                    page.detail(detail -> detail.editor(form -> form
                            .title("智能模型平台设置")
                            .field("tenantRegistrationEnabled", field -> field.label("允许租户配置模型"))));
                    page.traits(traits -> traits.operations(operations -> operations.standardCrud()));
                }))
                .build();
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
    }

    @Override
    protected void bindScope(AiModelPlatformSetting record, HttpServletRequest request) {
    }

    @Override
    protected boolean inScope(AiModelPlatformSetting record, HttpServletRequest request) {
        return true;
    }
}
