package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.database.core.orm.Criteria;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.web.NestedEnabledTreeCrudWebSupport;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = DictionaryCategoryService.MODULE_ALIAS, title = "平台数据字典类目", route = "/config/dictionaries")
@PlatformMenu(parent = PlatformMenuGroups.BUSINESS_SUPPORT, title = "字典管理", order = 10)
@RequestMapping({"/platform.dictionary_category", "/platform.application/{applicationAlias}/dictionary-categories"})
public class DictionaryCategoryWebController
        extends NestedEnabledTreeCrudWebSupport<DictionaryCategory, DictionaryCategoryService>
        implements StaticModuleUiContributor {

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(DictionaryCategoryService.MODULE_ALIAS)
                .editors(editors -> editors.defaultEditor(form -> form
                        .title("字典类目")
                        .field("applicationAlias", field -> field.label("所属应用").required().readOnly())
                        .field("alias", field -> field.label("类目 alias").required())
                        .field("categoryKind", field -> field.label("类目类型").required().uiType("select"))
                        .field("title", field -> field.label("类目名称").required())
                        .field("enabled", field -> field.label("启用状态").uiType("enabledStatus"))))
                .build();
    }

    @Override
    protected Criteria treeScopeCriteria(HttpServletRequest request) {
        String applicationAlias = applicationAlias(request);
        return applicationAlias == null ? Criteria.of() : Criteria.of().eq("applicationAlias", applicationAlias);
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
        return value == null || value.isBlank() ? null : value;
    }
}
