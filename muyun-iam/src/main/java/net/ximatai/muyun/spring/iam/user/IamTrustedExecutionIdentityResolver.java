package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegation;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Re-resolves real IAM accounts and the exact original delegation, without storing credentials. */
@Service
public final class IamTrustedExecutionIdentityResolver implements TrustedExecutionIdentityResolver {
    private final UserAccountService accounts;
    private final ActiveTenantVerifier tenants;
    private final UserAccountIdentity identities;
    private final ObjectProvider<EmployeeDelegationService> delegations;
    private final Clock clock;

    public IamTrustedExecutionIdentityResolver(UserAccountService accounts, ActiveTenantVerifier tenants,
            UserSessionCollaborators collaborators, ObjectProvider<EmployeeDelegationService> delegations, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts); this.tenants = Objects.requireNonNull(tenants);
        this.identities = new UserAccountIdentity(collaborators); this.delegations = Objects.requireNonNull(delegations);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public Optional<Reference> capture(String moduleAlias, String actionCode) {
        scope(moduleAlias, actionCode);
        var actor = CurrentUserContext.currentUser().orElse(null);
        if (actor == null || actor.passwordChangeRequired()) return Optional.empty();
        try (var ignored = accountScope(actor.tenantId())) {
            var account = account(actor.userId(), actor.tenantId());
            if (account == null || account.getCreatedAt() == null || actor.system() != (account.getTenantId() == null))
                return Optional.empty();
            var acting = ActingContextHolder.current().orElse(null);
            Delegation reference = null;
            if (acting != null) {
                if (!acting.matches(moduleAlias, actionCode) || !actor.userId().equals(acting.operator().userId())) return Optional.empty();
                var service = delegations.getIfAvailable();
                var grant = service == null ? null : service.selectActiveRaw(acting.delegationId());
                if (!validGrant(grant, account.getTenantId())) return Optional.empty();
                ActingContext restored;
                try (var noPrincipal = ActingContextHolder.without()) {
                    restored = resolveGrant(service, actor, grant, acting.principal().employeePositionId(),
                            acting.delegatePositionId(), moduleAlias, actionCode);
                }
                if (!samePrincipal(acting, restored)) return Optional.empty();
                reference = reference(grant, acting.principal().employeePositionId(), acting.delegatePositionId());
            }
            return Optional.of(new Reference(account.getId(), account.getTenantId(), account.getCreatedAt(), reference));
        }
    }

    @Override public Optional<Identity> resolve(Reference reference, String moduleAlias, String actionCode) {
        Objects.requireNonNull(reference, "reference"); scope(moduleAlias, actionCode);
        try (var noActor = CurrentUserContext.without(); var noPrincipal = ActingContextHolder.without();
             var ignored = accountScope(reference.accountTenantId())) {
            var account = account(reference.userId(), reference.accountTenantId());
            if (account == null || !reference.accountCreatedAt().equals(account.getCreatedAt())) return Optional.empty();
            var user = identities.currentUserOf(account, false);
            ActingContext acting = null;
            if (reference.delegation() != null) {
                var service = delegations.getIfAvailable();
                var grant = service == null ? null : service.selectActiveRaw(reference.delegation().id());
                var selected = reference.delegation();
                if (!validGrant(grant, account.getTenantId()) || !selected.equals(reference(grant,
                        selected.selectedPrincipalPositionId(), selected.selectedDelegatePositionId()))) return Optional.empty();
                try (var actor = CurrentUserContext.use(user)) {
                    acting = resolveGrant(service, user, grant, selected.selectedPrincipalPositionId(),
                            selected.selectedDelegatePositionId(), moduleAlias, actionCode);
                }
            }
            return Optional.of(new Identity(user, acting));
        }
    }

    private UserAccount account(String id, String tenant) {
        if (tenant != null) tenants.verifyActiveTenant(tenant);
        // This is an IAM identity lookup, not an authorized business query. Match the actual
        // account tenant explicitly, including platform accounts, without impersonating a system user.
        var account = accounts.getDao().query(Criteria.of().eq("id", id).eqNullable("tenantId", tenant)
                .eq("deleted", false).eq("enabled", true), PageRequest.of(1, 1)).stream().findFirst().orElse(null);
        return account == null || !id.equals(account.getId()) || !Objects.equals(tenant, account.getTenantId())
                || !Boolean.TRUE.equals(account.getEnabled()) || Boolean.TRUE.equals(account.getDeleted())
                || accounts.passwordChangeRequired(account, clock.instant()) ? null : account;
    }
    private ActingContext resolveGrant(EmployeeDelegationService service, CurrentUser user, EmployeeDelegation grant,
                                       String principalPosition, String delegatePosition, String module, String action) {
        var acting = service.resolveActingContext(user, grant.getDelegateEmployeeId(), delegatePosition,
                grant.getPrincipalEmployeeId(), principalPosition, module, action, clock.instant());
        if (!grant.getId().equals(acting.delegationId()))
            throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("The original delegation is no longer selected");
        return acting;
    }
    private static boolean validGrant(EmployeeDelegation grant, String tenant) {
        return grant != null && Objects.equals(grant.getTenantId(), tenant) && Boolean.TRUE.equals(grant.getEnabled())
                && !Boolean.TRUE.equals(grant.getDeleted()) && grant.getCreatedAt() != null;
    }
    private static Delegation reference(EmployeeDelegation grant, String selectedPrincipal, String selectedDelegate) {
        return new Delegation(grant.getId(), grant.getCreatedAt(), grant.getPrincipalEmployeeId(), grant.getPrincipalPositionId(),
                grant.getDelegateEmployeeId(), grant.getDelegatePositionId(), selectedPrincipal, selectedDelegate);
    }
    private static boolean samePrincipal(ActingContext left, ActingContext right) {
        return Objects.equals(left.principal().employeeId(), right.principal().employeeId())
                && Objects.equals(left.principal().employeePositionId(), right.principal().employeePositionId())
                && Objects.equals(left.delegatePositionId(), right.delegatePositionId());
    }
    private static TenantContext.Scope accountScope(String tenant) {
        return tenant == null ? TenantContext.system("resolve a referenced platform IAM account") : TenantContext.use(tenant);
    }
    private static void scope(String module, String action) {
        PlatformNameRules.requireModuleAlias(module); PlatformNameRules.requireActionCode(action, "actionCode");
    }
}
