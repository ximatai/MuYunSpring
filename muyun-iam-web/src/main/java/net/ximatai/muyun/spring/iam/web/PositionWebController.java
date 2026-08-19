package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageNavigatorInitialSelectionPolicy;
import net.ximatai.muyun.spring.platform.web.PageNavigatorSingleResultPolicy;
import net.ximatai.muyun.spring.platform.web.PageNavigatorSourceScope;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.StaticModuleWebControllerAdapter;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.MutationTenantScopeResolver;
import net.ximatai.muyun.spring.iam.position.Position;
import net.ximatai.muyun.spring.iam.position.PositionCategory;
import net.ximatai.muyun.spring.iam.position.PositionCategoryService;
import net.ximatai.muyun.spring.iam.position.PositionService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;


@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = PositionService.MODULE_ALIAS, title = "岗位管理")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, title = "岗位管理", order = 40)
@RequestMapping("/iam.position")
public class PositionWebController extends StaticModuleWebControllerAdapter<PositionService> implements
        CrudWeb<Position, PositionService>,
        MutationTenantScopeResolver<Position>,
        StaticModuleUiContributor {

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
        return ModuleUiDefinition.builder(PositionService.MODULE_ALIAS)
                .page(PageTemplates.listDetailCard(page -> page
                        .navigator(navigator -> navigator
                                .level("tenant", level -> level
                                        .microList("iam.tenant", "租户", "搜索租户")
                                        .sourceScope(PageNavigatorSourceScope.CURRENT_TENANT)
                                        .singleResultPolicy(PageNavigatorSingleResultPolicy.AUTO_SELECT_AND_HIDE))
                                .level("category", level -> level
                                        .tree(PositionCategoryService.MODULE_ALIAS, "岗位分类", "搜索岗位分类")
                                        .manageable()
                                        .initialSelectionPolicy(PageNavigatorInitialSelectionPolicy.FIRST_RECORD))
                                .bindSessionToList("tenantId", "tenantId")
                                .bindNavigatorToNavigator("tenant", "category", "tenantId")
                                .bindNavigatorToList("category", "categoryId"))
                        .list(list -> list.fields(fields -> fields
                                .title("岗位列表")
                                .field("code", field -> field.label("岗位编码").width("160px"))
                                .field("title", field -> field.label("岗位名称").width("180px"))
                                .field("description", field -> field.label("说明"))
                                .field("enabled", field -> field.label("状态").uiType("enabledStatus")
                                        .width("90px").align("center"))))
                        .detail(detail -> detail.editor(form -> form
                                .title("岗位档案")
                                .field("categoryId", field -> field.label("所属分类").required().readOnly())
                                .field("code", field -> field.label("岗位编码").required())
                                .field("title", field -> field.label("岗位名称").required())
                                .field("description", field -> field.label("说明"))
                                .field("enabled", field -> field.label("启用状态").uiType("enabledStatus"))))
                        .traits(traits -> traits.standardCrud().enabledStatus().responsiveDetailSurface())))
                .build();
    }
}
