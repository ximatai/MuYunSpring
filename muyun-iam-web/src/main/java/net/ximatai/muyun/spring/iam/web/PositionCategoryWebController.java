package net.ximatai.muyun.spring.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.web.MutationTenantScopeResolver;
import net.ximatai.muyun.spring.web.TreeScope;
import net.ximatai.muyun.spring.web.ScopedTreeWebProjectionPolicy;
import net.ximatai.muyun.spring.web.TreeWebQuerySupport;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.web.NavigatorReferenceWeb;
import net.ximatai.muyun.spring.web.NavigatorReferenceTreeWeb;
import net.ximatai.muyun.spring.iam.position.PositionCategory;
import net.ximatai.muyun.spring.iam.position.PositionCategoryService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = "iam.position_category", title = "岗位分类")
@StaticModuleOpenApi
@RequestMapping("/iam.position_category")
public class PositionCategoryWebController extends WebSupport<PositionCategoryService> implements
        CrudWeb<PositionCategory, PositionCategoryService>,
        NavigatorReferenceWeb<PositionCategory, PositionCategoryService>,
        NavigatorReferenceTreeWeb<PositionCategory, PositionCategoryService>,
        ScopedTreeWebProjectionPolicy<PositionCategory, PositionCategoryService>,
        MutationTenantScopeResolver<PositionCategory>,
        StaticModuleUiContributor {
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(PositionCategoryService.MODULE_ALIAS)
                .page(PageTemplates.treeManagement(page -> page
                        .detail(detail -> detail.editor(form -> form
                                .title("岗位分类")
                                .field("title", field -> field.label("分类名称").required())
                                .field("code", field -> field.label("分类编码").required())
                                .field("description", field -> field.label("说明"))))))
                .build();
    }
    @Override
    public TreeScope treeScope(HttpServletRequest request) {
        String tenantId = TreeWebQuerySupport.externalQueryText(request, "tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            return TreeScope.none();
        }
        return TreeScope.tenant(Criteria.of().eq("tenantId", tenantId), tenantId);
    }

    @Override
    public Optional<String> tenantIdForCreate(PositionCategory record) {
        return Optional.ofNullable(record == null ? null : record.getTenantId()).filter(value -> !value.isBlank());
    }

    @Override
    public Optional<String> tenantIdForUpdate(String id, PositionCategory record) {
        PositionCategory existing = service().select(id);
        return Optional.ofNullable(existing == null ? null : existing.getTenantId()).filter(value -> !value.isBlank());
    }

    @Override
    public Optional<String> tenantIdForExistingRecord(String id) {
        PositionCategory existing = service().select(id);
        return Optional.ofNullable(existing == null ? null : existing.getTenantId()).filter(value -> !value.isBlank());
    }
}
