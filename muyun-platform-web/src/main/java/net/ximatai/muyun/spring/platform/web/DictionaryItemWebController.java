package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.web.NestedEnabledTreeCrudWebSupport;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategory;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItem;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@PlatformStaticActionContribution(
        targetModule = DictionaryCategoryService.MODULE_ALIAS,
        resource = "item",
        resourceTitle = "字典项"
)
@RequestMapping({
        "/platform.dictionary_category/categories/{categoryId}/items",
        "/platform.application/{applicationAlias}/dictionary-categories/{categoryAlias}/items"
})
public class DictionaryItemWebController
        extends NestedEnabledTreeCrudWebSupport<DictionaryItem, DictionaryItemService>
        implements StaticModuleUiContributor {

    private static final String RESOURCE = "item";

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(DictionaryCategoryService.MODULE_ALIAS)
                .editorContribution(RESOURCE, form -> form
                        .title("字典项")
                        .field(RESOURCE, "categoryId", field -> field.label("所属类目").readOnly())
                        .field(RESOURCE, "code", field -> field.label("字典项编码").required())
                        .field(RESOURCE, "title", field -> field.label("字典项名称").required())
                        .field(RESOURCE, "parentId", field -> field.label("上级字典项").uiType("recordPicker"))
                        .field(RESOURCE, "enabled", field -> field.label("启用状态").uiType("enabledStatus")))
                .build();
    }

    @Override
    protected Criteria treeScopeCriteria(HttpServletRequest request) {
        return Criteria.of().eq("categoryId", category(request).getId());
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        criteria.eq("categoryId", category(request).getId());
    }

    @Override
    protected void bindScope(DictionaryItem record, HttpServletRequest request) {
        DictionaryCategory category = category(request);
        record.setCategoryId(category.getId());
        record.setCategoryAlias(category.getAlias());
    }

    @Override
    protected boolean inScope(DictionaryItem record, HttpServletRequest request) {
        return Objects.equals(record.getCategoryId(), category(request).getId());
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "dictionary item does not belong to category: " + category(request).getId() + "." + id;
    }

    private DictionaryCategory category(HttpServletRequest request) {
        String categoryId = pathVariable(request, "categoryId");
        if (categoryId != null && !categoryId.isBlank()) {
            return service().category(categoryId);
        }
        String applicationAlias = pathVariable(request, "applicationAlias");
        String categoryAlias = pathVariable(request, "categoryAlias");
        return service().category(applicationAlias, categoryAlias);
    }
}
