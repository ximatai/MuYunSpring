package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.localization.ConfiguredTextSeedTargetProvider;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** System purpose identifies presets; literal user role names never identify a baseline. */
@Service
public class RoleConfiguredTextSeedTargets implements ConfiguredTextSeedTargetProvider {
    private final RoleService roles;
    public RoleConfiguredTextSeedTargets(RoleService roles) { this.roles = Objects.requireNonNull(roles, "roles must not be null"); }

    @Override public List<Target> derivedTargets(String moduleAlias, List<TextReference> originals, String tenantId) {
        if (!RoleService.MODULE_ALIAS.equals(moduleAlias)) return List.of();
        var criteria = Criteria.of().eq("builtIn", true).eq("systemManaged", true)
                .in("systemPurpose", List.of(RoleSystemPurpose.TENANT_ADMIN, RoleSystemPurpose.ORGANIZATION_ADMIN));
        if (tenantId != null) criteria.eq("tenantId", tenantId);
        var current = new HashSet<>(originals);
        List<Target> result = new ArrayList<>();
        try (var ignored = TenantContext.bypassTenantFilter("system role translation baseline targets")) {
            for (var role : roles.list(criteria, new PageRequest(0, Integer.MAX_VALUE))) {
                if (role.getTenantId() == null || role.getTenantId().isBlank()
                        || tenantId != null && !tenantId.equals(role.getTenantId())
                        || !Boolean.TRUE.equals(role.getBuiltIn()) || !Boolean.TRUE.equals(role.getSystemManaged())) continue;
                Map<String, TextReference> baselines;
                if (role.getSystemPurpose() == RoleSystemPurpose.TENANT_ADMIN && role.getOwnerScopeType() == RoleOwnerScopeType.TENANT
                        && role.getTenantId().equals(role.getOwnerScopeId())) baselines = DefaultTenantRoleProvisioner.textBaselines();
                else if (role.getSystemPurpose() == RoleSystemPurpose.ORGANIZATION_ADMIN && role.getOwnerScopeType() == RoleOwnerScopeType.ORGANIZATION
                        && role.getOwnerScopeId() != null && !role.getOwnerScopeId().isBlank()) baselines = DefaultOrganizationRoleProvisioner.textBaselines();
                else continue;
                var sources = roles.recordTextSources(role);
                baselines.forEach((field, baseline) -> {
                    var target = sources.get(field);
                    if (target != null && current.contains(target)) result.add(new Target(baseline, target));
                });
            }
        }
        return List.copyOf(result);
    }
}
