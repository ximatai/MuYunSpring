package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.ModuleUiBindingKey;
import net.ximatai.muyun.spring.platform.web.ModuleUiField;
import net.ximatai.muyun.spring.platform.web.ModuleUiNavigatorKey;
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

    private static final ModuleUiNavigatorKey TENANT_NAVIGATOR = ModuleUiNavigatorKey.of("tenant");
    private static final ModuleUiNavigatorKey CATEGORY_NAVIGATOR = ModuleUiNavigatorKey.of("category");
    private static final ModuleUiField TENANT_ID = ModuleUiField.of("tenantId");
    private static final ModuleUiField CATEGORY_ID = ModuleUiField.of("categoryId");
    private static final ModuleUiField CODE = ModuleUiField.of("code");
    private static final ModuleUiField TITLE = ModuleUiField.of("title");
    private static final ModuleUiField DESCRIPTION = ModuleUiField.of("description");
    private static final ModuleUiField ENABLED = ModuleUiField.of("enabled");

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
                                .level(TENANT_NAVIGATOR, level -> level
                                        .microList("iam.tenant", "租户", "搜索租户")
                                        .sourceScope(PageNavigatorSourceScope.CURRENT_TENANT)
                                        .singleResultPolicy(PageNavigatorSingleResultPolicy.AUTO_SELECT_AND_HIDE))
                                .level(CATEGORY_NAVIGATOR, level -> level
                                        .tree(PositionCategoryService.MODULE_ALIAS, "岗位分类", "搜索岗位分类")
                                        .manageable()
                                        .initialSelectionPolicy(PageNavigatorInitialSelectionPolicy.FIRST_RECORD))
                                .bindNavigatorToNavigator(TENANT_NAVIGATOR, CATEGORY_NAVIGATOR, TENANT_ID)
                                .bindNavigatorToList(CATEGORY_NAVIGATOR, CATEGORY_ID))
                        .list(list -> list.fields(fields -> fields
                                .title("岗位列表")
                                .field(CODE, field -> field.label("岗位编码").width("160px"))
                                .field(TITLE, field -> field.label("岗位名称").width("180px"))
                                .field(DESCRIPTION, field -> field.label("说明"))
                                .field(ENABLED, field -> field.label("状态").uiType("enabledStatus")
                                        .width("90px").align("center"))))
                        .detail(detail -> detail.editor(form -> form
                                .title("岗位档案")
                                .field(CATEGORY_ID, field -> field.label("所属分类").required().readOnly())
                                .field(CODE, field -> field.label("岗位编码").required())
                                .field(TITLE, field -> field.label("岗位名称").required())
                                .field(DESCRIPTION, field -> field.label("说明"))
                                .field(ENABLED, field -> field.label("启用状态").uiType("enabledStatus"))))
                        .traits(traits -> traits.standardCrud().enabledStatus().responsiveDetailSurface())))
                .build();
    }
}
