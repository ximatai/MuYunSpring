package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.database.core.orm.Criteria;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.web.NestedEnabledTreeCrudWebSupport;
import net.ximatai.muyun.spring.web.NavigatorReferenceTreeWeb;
import net.ximatai.muyun.spring.web.TreeWebQuerySupport;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryKind;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = DictionaryCategoryService.MODULE_ALIAS, title = "平台数据字典类目")
@PlatformMenu(parent = PlatformMenuGroups.BUSINESS_SUPPORT, title = "字典管理", order = 10)
@RequestMapping({"/platform.dictionary_category", "/platform.application/{applicationAlias}/dictionary-categories"})
public class DictionaryCategoryWebController
        extends NestedEnabledTreeCrudWebSupport<DictionaryCategory, DictionaryCategoryService>
        implements NavigatorReferenceTreeWeb<DictionaryCategory, DictionaryCategoryService>,
        StaticModuleUiContributor {

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(DictionaryCategoryService.MODULE_ALIAS)
                .page(PageTemplates.treeManagement(page -> page
                        .navigator(navigator -> navigator
                                .level("application", level -> level
                                        .microList(ApplicationService.MODULE_ALIAS, "应用", "搜索应用")
                                        .initialSelectionPolicy(PageNavigatorInitialSelectionPolicy.FIRST_RECORD))
                                .level("category", level -> level
                                        .tree(DictionaryCategoryService.MODULE_ALIAS, "字典类目", "搜索字典类目")
                                        .manageable())
                                .bindNavigatorToNavigator("application", "category", "applicationAlias"))
                        .treeResource("item", "category", "categoryId", resource -> resource
                                .availableWhenEquals("categoryKind", DictionaryCategoryKind.DICTIONARY.getCode())
                                .title("字典项")
                                .emptyDescription("请选择字典类目，或在类目中维护后新增字典项")
                                .createTitle("新建字典项"))
                        .detail(detail -> detail
                                .emptyDescription("请选择字典项，或新建根字典项")
                                .editor(form -> form
                                        .title("字典类目")
                                        .field("applicationAlias", field -> field.label("所属应用").required().hidden())
                                        .field("parentId", field -> field.label("上级类目").recordPicker()
                                                .treeRootTitle("根类目"))
                                        .field("alias", field -> field.label("类目 alias").required())
                                        .field("categoryKind", field -> field.label("类目类型").required().select())
                                        .field("title", field -> field.label("类目名称").required())
                                        .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()))))
                .build();
    }

    @Override
    protected Criteria treeScopeCriteria(HttpServletRequest request) {
        String applicationAlias = applicationAlias(request);
        return applicationAlias == null ? Criteria.of() : Criteria.of().eq("applicationAlias", applicationAlias);
    }

    /**
     * A navigator-dependent category tree has no meaningful root before its application is selected.
     * Return an empty tree for that transient UI state instead of invoking the unscoped service API.
     */
    @Override
    public List<DictionaryCategory> treeChildren(HttpServletRequest request, String parentId) {
        return scopedTreeChildren(request, parentId);
    }

    /** Keep navigator reference reads on the same application-scoped tree projection. */
    @Override
    public List<DictionaryCategory> treeChildrenForAction(HttpServletRequest request,
                                                          PlatformAction action,
                                                          String parentId) {
        return scopedTreeChildren(request, parentId);
    }

    private List<DictionaryCategory> scopedTreeChildren(HttpServletRequest request, String parentId) {
        String applicationAlias = applicationAlias(request);
        return applicationAlias == null
                ? List.of()
                : service().children(Criteria.of().eq("applicationAlias", applicationAlias), parentId);
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        String applicationAlias = applicationAlias(request);
        if (applicationAlias != null) {
            criteria.eq("applicationAlias", applicationAlias);
        }
    }

    @Override
    protected void bindScope(DictionaryCategory record, HttpServletRequest request) {
        String applicationAlias = applicationAlias(request);
        if (applicationAlias != null) {
            record.setApplicationAlias(applicationAlias);
        }
    }

    @Override
    protected boolean inScope(DictionaryCategory record, HttpServletRequest request) {
        String applicationAlias = applicationAlias(request);
        return applicationAlias == null || Objects.equals(record.getApplicationAlias(), applicationAlias);
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "dictionary category does not belong to application: " + applicationAlias(request) + "." + id;
    }

    private String applicationAlias(HttpServletRequest request) {
        String value = pathVariable(request, "applicationAlias");
        if (value == null || value.isBlank()) {
            value = TreeWebQuerySupport.externalQueryText(request, "applicationAlias");
        }
        return value == null || value.isBlank() ? null : value;
    }
}
