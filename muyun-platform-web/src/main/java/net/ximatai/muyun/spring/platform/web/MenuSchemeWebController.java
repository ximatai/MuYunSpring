package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.platform.menu.MenuScheme;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import net.ximatai.muyun.spring.web.NavigatorReferenceWeb;


@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = MenuSchemeService.MODULE_ALIAS, title = "平台菜单方案")
@StaticModuleOpenApi
@RequestMapping("/platform.menu_scheme")
public class MenuSchemeWebController extends net.ximatai.muyun.spring.web.WebSupport<MenuSchemeService>
        implements CrudWeb<MenuScheme, MenuSchemeService>, NavigatorReferenceWeb<MenuScheme, MenuSchemeService>,
        StaticModuleUiContributor {

    private static final String SCHEME_EDITOR = "menu_scheme_editor";

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(MenuSchemeService.MODULE_ALIAS)
                .editors(editors -> editors.editor(SCHEME_EDITOR, form -> form
                        .title("菜单方案")
                        .field("alias", field -> field.label("方案 alias").required()
                                .enabledWhen(UiFormula.booleanExpression("!(PRESENT({id}))")))
                        .field("title", field -> field.label("方案名称").required())
                        .field("scopeType", field -> field.label("适用范围").required().uiType("select"))
                        .field("tenantId", field -> field.label("适用租户").required(
                                        UiRule.formula(UiFormula.booleanExpression("{scopeType} == 'tenant' || {scopeType} == 'organization'")))
                                .visible(UiRule.formula(UiFormula.booleanExpression("{scopeType} == 'tenant' || {scopeType} == 'organization'")))
                                .uiType("recordPicker"))
                        .field("organizationId", field -> field.label("适用机构").required(
                                        UiRule.formula(UiFormula.booleanExpression("{scopeType} == 'organization'")))
                                .visible(UiRule.formula(UiFormula.booleanExpression("{scopeType} == 'organization'")))
                                .uiType("recordPicker"))
                        .field("enabled", field -> field.label("启用状态").uiType("enabledStatus"))))
                .build();
    }
}
