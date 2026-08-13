package net.ximatai.muyun.spring.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.TreeScope;
import net.ximatai.muyun.spring.web.MutationTenantScopeResolver;
import net.ximatai.muyun.spring.web.ScopedTreeWebProjectionPolicy;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class, alias = "iam.organization", title = "机构管理", route = "/iam/organizations")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, order = 20)
@RequestMapping("/iam.organization")
public class OrganizationWebController extends WebSupport<OrganizationService> implements
        CrudWeb<Organization, OrganizationService>,
        ScopedTreeWebProjectionPolicy<Organization, OrganizationService>,
        MutationTenantScopeResolver<Organization> {
    @Override
    public TreeScope treeScope(HttpServletRequest request) {
        String tenantId = resolveTreeTenantId(request.getParameter("tenantId"));
        if (tenantId == null) {
            return TreeScope.none();
        }
        return TreeScope.tenant(Criteria.of().eq(StandardEntitySchema.TENANT_ID_FIELD, tenantId), tenantId);
    }

    @Override
    public Optional<String> tenantIdForCreate(Organization record) {
        return tenantIdOf(record);
    }

    @Override
    public Optional<String> tenantIdForUpdate(String id, Organization record) {
        Organization existing = service().select(id);
        return tenantIdOf(existing == null ? record : existing);
    }

    @Override
    public Optional<String> tenantIdForExistingRecord(String id) {
        return tenantIdOf(service().select(id));
    }

    private String resolveTreeTenantId(String requestedTenantId) {
        String normalized = requestedTenantId == null || requestedTenantId.isBlank()
                ? null
                : requestedTenantId.trim();
        if (TenantContext.isSystem()) {
            if (normalized == null) {
                return null;
            }
            return normalized;
        }
        String currentTenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformException("iam.organization tree requires tenant context"));
        if (normalized != null && !currentTenantId.equals(normalized)) {
            throw new PlatformException("organization tree tenantId must match current tenant");
        }
        return currentTenantId;
    }

    private Optional<String> tenantIdOf(Organization organization) {
        return Optional.ofNullable(organization == null ? null : organization.getTenantId())
                .filter(value -> !value.isBlank());
    }
}
