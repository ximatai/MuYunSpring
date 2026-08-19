package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.NavigatorReferenceTreeWeb;
import net.ximatai.muyun.spring.web.NavigatorReferenceWeb;
import net.ximatai.muyun.spring.web.ScopedTreeWebProjectionPolicy;
import net.ximatai.muyun.spring.web.TreeScope;
import net.ximatai.muyun.spring.web.TreeWebQuerySupport;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRefreshResult;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = PlatformModuleService.MODULE_ALIAS, title = "平台模块")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.MODELING, title = "模块管理", order = 20)
@RequestMapping("/platform.module")
public class PlatformModuleWebController extends StaticModuleWebControllerAdapter<PlatformModuleService> implements
        CrudWeb<PlatformModule, PlatformModuleService>,
        NavigatorReferenceWeb<PlatformModule, PlatformModuleService>,
        NavigatorReferenceTreeWeb<PlatformModule, PlatformModuleService>,
        ScopedTreeWebProjectionPolicy<PlatformModule, PlatformModuleService>,
        SystemScope<PlatformModuleService>,
        StaticModuleUiContributor {

    private PlatformDynamicRuntimeRefreshService runtimeRefreshService;
    private PlatformOpenApiCatalogService openApiCatalogService;

    @Autowired
    public PlatformModuleWebController(PlatformDynamicRuntimeRefreshService runtimeRefreshService) {
        this.runtimeRefreshService = runtimeRefreshService;
    }

    public PlatformModuleWebController() {
    }

    /**
     * The module catalog is a tree scoped by the selected application.  The application is a
     * navigator context, not a synthetic node in the module tree: it constrains tree reads and
     * supplies the default for new root modules, while {@code parentId} remains the module-tree
     * relationship used for child creation.
     */
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(PlatformModuleService.MODULE_ALIAS)
                .page(PageTemplates.treeManagement(page -> page
                        .navigator(navigator -> navigator
                                .level("application", level -> level.microList(ApplicationService.MODULE_ALIAS,
                                        "应用", "搜索应用")
                                        .manageable(PageNavigatorManagementAction.CREATE)
                                        .initialSelectionPolicy(PageNavigatorInitialSelectionPolicy.FIRST_RECORD))
                                .bindNavigatorToList("application", "applicationAlias")
                                .bindNavigatorToPickerQuery("application", "parentId", "applicationAlias"))
                        .detail(detail -> detail
                                .emptyDescription("请选择模块，或新建根模块")
                                .createTitle("新建模块")
                                .display(form -> form.title("模块信息")
                                        .field("applicationAlias", field -> field.label("所属应用"))
                                        .field("alias", field -> field.label("模块 alias"))
                                        .field("title", field -> field.label("模块名称"))
                                        .field("parentId", field -> field.label("上级模块").treeRootTitle("根模块"))
                                        .field("moduleKind", field -> field.label("模块类型"))
                                        .field("entryType", field -> field.label("入口类型"))
                                        .field("entryRoute", field -> field.label("内部路由")
                                                .visible(UiRule.formula(UiFormula.booleanExpression(
                                                        "{entryType} == 'route'"))))
                                        .field("entryExternalUrl", field -> field.label("外部链接")
                                                .visible(UiRule.formula(UiFormula.booleanExpression(
                                                        "{entryType} == 'link'")))))
                                .editor(form -> form.title("模块")
                                        .field("alias", field -> field.label("模块 alias").required()
                                                .enabledWhen(UiFormula.booleanExpression("!(PRESENT({id}))")))
                                        .field("title", field -> field.label("模块名称").required())
                                        .field("applicationAlias", field -> field.label("所属应用").required().hidden())
                                        .field("parentId", field -> field.label("上级模块").uiType("recordPicker"))
                                        .field("moduleKind", field -> field.label("模块类型").required().uiType("select"))
                                        .field("entryType", field -> field.label("入口类型").required().uiType("select"))
                                        .field("entryRoute", field -> field.label("内部路由")
                                                .visible(UiRule.formula(UiFormula.booleanExpression(
                                                        "{entryType} == 'route'"))))
                                        .field("entryExternalUrl", field -> field.label("外部链接")
                                                .visible(UiRule.formula(UiFormula.booleanExpression(
                                                        "{entryType} == 'link'"))))
                                        .field("enabled", field -> field.label("启用状态").uiType("enabledStatus"))))
                        .traits(traits -> traits.standardCrud().enabledStatus())))
                .build();
    }

    @Autowired
    void configureOpenApiCatalog(PlatformOpenApiCatalogService value) {
        this.openApiCatalogService = value;
    }

    @GetMapping("/openapi/catalog")
    @ActionEndpoint(PlatformAction.VIEW)
    public List<OpenApiModuleCatalogItem> openApiCatalog() {
        // Module configuration itself is read under SystemScope. Capture the request context
        // before that switch so document discovery reflects the context which will later call
        // the module endpoint, rather than the administrative read scope.
        String requestTenantId = TenantContext.currentTenantId().orElse(null);
        return webScope(() -> openApiCatalogService.discover(requestTenantId));
    }

    @Override
    public TreeScope treeScope(HttpServletRequest request) {
        String applicationAlias = requestedApplicationAlias(request);
        if (applicationAlias == null || applicationAlias.isBlank()) {
            return TreeScope.none();
        }
        return TreeScope.of(Criteria.of().eq("applicationAlias",
                PlatformNameRules.requireApplicationAlias(applicationAlias)));
    }

    @Override
    public TreeScope treeScope(HttpServletRequest request, PlatformModule record) {
        return record == null ? treeScope(request) : applicationTreeScope(record.getApplicationAlias());
    }

    @PostMapping("/{moduleAlias}/runtime/refresh")
    @CustomActionEndpoint(value = "refreshDynamicRuntime", title = "刷新动态运行态",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "moduleAlias")
    public DynamicModuleRefreshResult refreshRuntime(@PathVariable String moduleAlias) {
        return webScope(() -> runtimeRefreshService.refresh(moduleAlias));
    }

    @PostMapping("/{moduleAlias}/runtime/execute-refresh")
    @CustomActionEndpoint(value = "executeRefreshDynamicRuntime", title = "执行刷新动态运行态",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "moduleAlias")
    public DynamicModuleRefreshResult executeRefreshRuntime(@PathVariable String moduleAlias) {
        return webScope(() -> runtimeRefreshService.executeRefresh(moduleAlias));
    }

    @PostMapping("/{moduleAlias}/runtime/preview-refresh")
    @CustomActionEndpoint(value = "previewRefreshDynamicRuntime", title = "预览刷新动态运行态",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "moduleAlias")
    public DynamicModuleRefreshResult previewRefreshRuntime(@PathVariable String moduleAlias) {
        return webScope(() -> runtimeRefreshService.previewRefresh(moduleAlias));
    }

    private TreeScope applicationTreeScope(String applicationAlias) {
        return TreeScope.of(Criteria.of().eq("applicationAlias",
                PlatformNameRules.requireApplicationAlias(applicationAlias)));
    }

    private String requestedApplicationAlias(HttpServletRequest request) {
        String queryValue = TreeWebQuerySupport.externalQueryText(request, "applicationAlias");
        return queryValue != null && !queryValue.isBlank() ? queryValue : null;
    }

}
