package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class RoleConfiguredTextSeedTargetsTest {
    @Test void usesSystemPurposeAndOwnershipRatherThanLiteralNames() {
        var roles = mock(RoleService.class);
        when(roles.getModuleAlias()).thenReturn(RoleService.MODULE_ALIAS);
        var tenant = role("tenant-a", "preset", RoleSystemPurpose.TENANT_ADMIN);
        var organization = role("tenant-a", "org-preset", RoleSystemPurpose.ORGANIZATION_ADMIN);
        organization.setOwnerScopeType(RoleOwnerScopeType.ORGANIZATION); organization.setOwnerScopeId("org-a");
        var other = role("tenant-b", "other-preset", RoleSystemPurpose.TENANT_ADMIN);
        var manual = role("tenant-a", "manual", RoleSystemPurpose.NONE);
        var unmarked = role("tenant-a", "unmarked", RoleSystemPurpose.TENANT_ADMIN); unmarked.setSystemManaged(false);
        var wrongOwner = role("tenant-a", "wrong-owner", RoleSystemPurpose.TENANT_ADMIN); wrongOwner.setOwnerScopeId("tenant-b");
        var rows = List.of(tenant, organization, other, manual, unmarked, wrongOwner);
        when(roles.list(any(Criteria.class), any(PageRequest.class))).thenReturn(rows);
        when(roles.recordTextSources(any())).thenCallRealMethod();
        var sources = rows.stream().flatMap(row -> roles.recordTextSources(row).values().stream()).toList();
        var provider = new RoleConfiguredTextSeedTargets(roles);
        try (var scope = TenantContext.system("role preset targets")) {
            assertThat(provider.derivedTargets(RoleService.MODULE_ALIAS, sources, "tenant-a"))
                    .extracting(target -> target.target().sourceId()).containsExactlyInAnyOrder(
                            sourcesFor(tenant, "title"), sourcesFor(tenant, "description"),
                            sourcesFor(organization, "title"), sourcesFor(organization, "description"));
            assertThat(provider.derivedTargets(RoleService.MODULE_ALIAS, sources, "tenant-b")).hasSize(2);
            assertThat(provider.derivedTargets("platform.menu", sources, null)).isEmpty();
        }
    }
    private String sourcesFor(Role role, String field) {
        return net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources.recordText(RoleService.MODULE_ALIAS,
                role.getId(), field, field.equals("title") ? role.getTitle() : role.getDescription()).sourceId();
    }
    private Role role(String tenant, String id, RoleSystemPurpose purpose) {
        var role = new Role(); role.setId(id); role.setTenantId(tenant); role.setOwnerScopeType(RoleOwnerScopeType.TENANT);
        role.setOwnerScopeId(tenant); role.setSystemPurpose(purpose); role.setBuiltIn(true); role.setSystemManaged(true);
        role.setTitle(purpose == RoleSystemPurpose.ORGANIZATION_ADMIN ? DefaultOrganizationRoleProvisioner.ORGANIZATION_ADMIN_ROLE_TITLE : RoleService.TENANT_ADMIN_ROLE_TITLE);
        role.setDescription(purpose == RoleSystemPurpose.ORGANIZATION_ADMIN ? DefaultOrganizationRoleProvisioner.ORGANIZATION_ADMIN_ROLE_DESCRIPTION : DefaultTenantRoleProvisioner.TENANT_ADMIN_ROLE_DESCRIPTION); return role;
    }
}
