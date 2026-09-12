package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScope;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScopeResolver;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves log-governance visibility from the exact role action being executed.
 *
 * <p>Only account role grants are governance grants. Organization visibility is expanded while
 * resolving the scope and later compared with the organization snapshot on each log fact.</p>
 */
@Service
public class IamBusinessLogReadScopeResolver implements BusinessLogReadScopeResolver {
    private final RoleService roleService;
    private final OrganizationService organizationService;

    public IamBusinessLogReadScopeResolver(RoleService roleService, OrganizationService organizationService) {
        this.roleService = Objects.requireNonNull(roleService, "roleService must not be null");
        this.organizationService = Objects.requireNonNull(organizationService, "organizationService must not be null");
    }

    @Override
    public BusinessLogReadScope resolve(String moduleAlias, String actionCode) {
        String validModuleAlias = requireText(moduleAlias, "moduleAlias");
        String validActionCode = requireText(actionCode, "actionCode");
        CurrentUser user = CurrentUserContext.currentUser().orElse(null);
        if (user == null) {
            return BusinessLogReadScope.denied();
        }
        if (user.system()) {
            return BusinessLogReadScope.platform();
        }
        String tenantId = user.tenantId();
        if (tenantId == null) {
            return BusinessLogReadScope.denied();
        }

        List<EffectiveRoleActionGrant> effectiveGrants = roleService.effectiveActionGrantsWithContext(
                user.userId(), validModuleAlias, validActionCode);
        LinkedHashSet<String> organizationIds = new LinkedHashSet<>();
        for (EffectiveRoleActionGrant effectiveGrant : effectiveGrants) {
            EffectiveRoleGrant roleGrant = effectiveGrant == null ? null : effectiveGrant.roleGrant();
            if (roleGrant == null || roleGrant.sourceType() != RoleAssignmentType.ACCOUNT) {
                continue;
            }
            ManagementScopeType managementScopeType = roleGrant.managementScopeType();
            if (managementScopeType == ManagementScopeType.PLATFORM) {
                // Platform-wide audit access belongs only to system identities (handled above).
                continue;
            }
            if (managementScopeType == ManagementScopeType.TENANT) {
                if (tenantId.equals(roleGrant.managementScopeId())) {
                    return BusinessLogReadScope.tenant(tenantId);
                }
                continue;
            }
            if (managementScopeType == ManagementScopeType.ORGANIZATION) {
                organizationIds.addAll(organizationIdsFor(roleGrant.managementScopeId()));
            }
        }
        return organizationIds.isEmpty()
                ? BusinessLogReadScope.denied()
                : BusinessLogReadScope.organization(tenantId, organizationIds);
    }

    private Set<String> organizationIdsFor(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return Set.of();
        }
        return organizationService.selfAndDescendantIds(organizationId).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
