package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.NavigatorReferenceWeb;
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
public class ApplicationWebController extends StaticModuleWebControllerAdapter<ApplicationService> implements
        CrudWeb<Application, ApplicationService>,
        SystemScope<ApplicationService>,
        NavigatorReferenceWeb<Application, ApplicationService>,
        StaticModuleUiContributor {
    private static final ModuleUiField ID = ModuleUiField.of("id");
    private static final ModuleUiField ALIAS = ModuleUiField.of("alias");
    private static final ModuleUiField TITLE = ModuleUiField.of("title");
    private static final ModuleUiField ENABLED = ModuleUiField.of("enabled");

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(ApplicationService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("应用列表")
                                .searchPlaceholder("搜索应用名称、alias 或 ID")
                                .emptyDescription("暂无应用").recordLabel("应用")
                                .fallbackTitle("未命名应用").titleField(TITLE.name()).secondaryField(ALIAS.name())
                                .mutedWhenDisabled())
                        .detail(detail -> detail.emptyDescription("请选择应用，或新建应用").createTitle("新建应用")
                                .editor(form -> form.title("应用")
                                        .field(ALIAS, field -> field.label("应用 alias").required()
                                                .enabledWhen(UiFormula.booleanExpression("!(PRESENT({" + ID.name() + "}))")))
                                        .field(TITLE, field -> field.label("应用名称").required())
                                        .field(ENABLED, field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle().recycleBin()))))
                .build();
    }
}
