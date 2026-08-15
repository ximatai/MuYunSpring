package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PlatformStaticActionContribution;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.MutationTenantScopeResolver;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.iam.position.Position;
import net.ximatai.muyun.spring.iam.position.PositionCategory;
import net.ximatai.muyun.spring.iam.position.PositionCategoryService;
import net.ximatai.muyun.spring.iam.position.PositionService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;


@RestController
@PlatformStaticActionContribution(
        targetModule = PositionCategoryService.MODULE_ALIAS,
        resource = "position",
        resourceTitle = "岗位"
)
@RequestMapping("/iam.position")
public class PositionWebController extends WebSupport<PositionService> implements
        CrudWeb<Position, PositionService>,
        MutationTenantScopeResolver<Position>,
        StaticModuleUiContributor {

    private static final String RESOURCE = "position";
    private PositionCategoryService positionCategoryService;

    @Autowired
    void setPositionCategoryService(PositionCategoryService positionCategoryService) {
        this.positionCategoryService = positionCategoryService;
    }

    @Override
    public Optional<String> tenantIdForCreate(Position record) {
        return tenantIdForCategory(record == null ? null : record.getCategoryId());
    }

    @Override
    public Optional<String> tenantIdForUpdate(String id, Position record) {
        Position existing = service().select(id);
        return tenantIdForCategory(existing == null ? (record == null ? null : record.getCategoryId()) : existing.getCategoryId());
    }

    @Override
    public Optional<String> tenantIdForExistingRecord(String id) {
        Position existing = service().select(id);
        return tenantIdForCategory(existing == null ? null : existing.getCategoryId());
    }

    private Optional<String> tenantIdForCategory(String categoryId) {
        if (categoryId == null || categoryId.isBlank() || positionCategoryService == null) return Optional.empty();
        PositionCategory category = positionCategoryService.select(categoryId);
        return Optional.ofNullable(category == null ? null : category.getTenantId()).filter(value -> !value.isBlank());
    }

    @Override
    public boolean supportsUnpagedQuery() {
        return true;
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(PositionCategoryService.MODULE_ALIAS)
                .editorContribution(RESOURCE, form -> form
                        .title("岗位")
                        .field(RESOURCE, "categoryId", field -> field.label("所属分类").required())
                        .field(RESOURCE, "code", field -> field.label("岗位编码").required())
                        .field(RESOURCE, "title", field -> field.label("岗位名称").required())
                        .field(RESOURCE, "description", field -> field.label("说明"))
                        .field(RESOURCE, "enabled", field -> field.label("启用状态").uiType("enabledStatus")))
                .build();
    }
}
