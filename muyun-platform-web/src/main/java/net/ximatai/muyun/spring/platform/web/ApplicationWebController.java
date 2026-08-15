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
                .listView(list -> list
                        .title("应用列表")
                        .field("alias", field -> field.label("应用 alias").width("180px"))
                        .field("title", field -> field.label("应用名称").width("240px"))
                        .field("enabled", field -> field.label("状态").uiType("enabledStatus")
                                .width("90px").align("center")))
                .formView(form -> form
                        .title("应用")
                        .field("alias", field -> field.label("应用 alias").required()
                                .enabledWhen(UiFormula.booleanExpression("!(PRESENT({id}))")))
                        .field("title", field -> field.label("应用名称").required())
                        .field("enabled", field -> field.label("启用状态").uiType("enabledStatus")))
                .build();
    }
}
