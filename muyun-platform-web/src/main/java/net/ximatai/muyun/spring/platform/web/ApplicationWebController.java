package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = ApplicationService.MODULE_ALIAS, title = "平台应用")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.MODELING, title = "应用管理", order = 10)
@RequestMapping("/platform.application")
public class ApplicationWebController extends WebSupport<ApplicationService> implements
        CrudWeb<Application, ApplicationService>,
        SystemScope<ApplicationService>,
        StaticModuleUiContributor {
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(ApplicationService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("应用列表")
                                .searchPlaceholder("搜索应用名称、alias 或 ID")
                                .emptyDescription("暂无应用").recordLabel("应用")
                                .fallbackTitle("未命名应用").titleField("title").secondaryField("alias")
                                .mutedWhenDisabled())
                        .detail(detail -> detail.emptyDescription("请选择应用，或新建应用").createTitle("新建应用")
                                .editor(form -> form.title("应用")
                                        .field("alias", field -> field.label("应用 alias").required()
                                                .enabledWhen(UiFormula.booleanExpression("!(PRESENT({id}))")))
                                        .field("title", field -> field.label("应用名称").required())
                                        .field("enabled", field -> field.label("启用状态").uiType("enabledStatus"))))
                        .traits(traits -> traits.standardCrud().enabledStatus().recycleBin())))
                .build();
    }
}
