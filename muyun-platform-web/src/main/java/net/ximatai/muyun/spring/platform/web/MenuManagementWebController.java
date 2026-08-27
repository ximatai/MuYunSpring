package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.web.ScopedTreeWebProjectionPolicy;
import net.ximatai.muyun.spring.web.TreeScope;
import net.ximatai.muyun.spring.web.TreeWebQuerySupport;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = MenuService.MODULE_ALIAS, title = "菜单管理")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "菜单管理", order = 10)
@RequestMapping("/platform.menu")
public class MenuManagementWebController extends WebSupport<MenuService>
        implements CrudWeb<Menu, MenuService>, ScopedTreeWebProjectionPolicy<Menu, MenuService>,
        StaticModuleUiContributor {
    private static final String SCHEME_NAVIGATOR = "scheme";
    private static final String SCHEME_EDITOR = "menu_scheme_editor";

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(MenuService.MODULE_ALIAS)
                .page(PageTemplates.treeManagement(page -> page
                        .navigator(navigator -> navigator
                                .level(SCHEME_NAVIGATOR, level -> level
                                        .microList(MenuSchemeService.MODULE_ALIAS, "菜单方案", "搜索菜单方案")
                                        .secondaryField("scopeTypeTitle")
                                        .manageable(SCHEME_EDITOR)
                                        .initialSelectionPolicy(PageNavigatorInitialSelectionPolicy.FIRST_RECORD))
                                .filterListByNavigator(SCHEME_NAVIGATOR, "schemeId",
                                        NavigatorListQueryMode.REQUIRED_SCOPE)
                                .prefillFormFromNavigator(SCHEME_NAVIGATOR, "schemeId")
                                .bindNavigatorToPickerQuery(SCHEME_NAVIGATOR, "parentId", "schemeId"))
                        .detail(detail -> detail
                                .emptyDescription("请选择菜单，或新建根菜单")
                                .editor(form -> form
                                        .title("菜单")
                                        .field("title", field -> field.label("菜单名称").required())
                                        .field("parentId", field -> field.label("上级菜单").recordPicker()
                                                .treeRootTitle("根菜单"))
                                        .field("moduleAlias", field -> field.label("关联模块").recordPicker())
                                        .field("openMode", field -> field.label("打开方式").select())
                                        .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()))))
                .build();
    }

    @Override
    public TreeScope treeScope(HttpServletRequest request) {
        Object scope = TreeWebQuerySupport.boundExternalQueryValue(request, "schemeId");
        String value = scope == null ? PageContextScopePolicy.requiredRecordScopeValues(List.of(
                PageContextBindingDefinition.navigatorList(SCHEME_NAVIGATOR, "schemeId",
                        NavigatorListQueryMode.REQUIRED_SCOPE))).get("schemeId").toString() : String.valueOf(scope);
        if (value.isBlank()) throw new IllegalArgumentException("menu tree requires scheme navigator context");
        return TreeScope.of(Criteria.of().eq("schemeId", value));
    }
}
