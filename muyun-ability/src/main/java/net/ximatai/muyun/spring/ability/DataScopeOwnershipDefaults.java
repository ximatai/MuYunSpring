package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.capability.DataScopeCapable;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;

import java.util.Optional;

final class DataScopeOwnershipDefaults {
    private DataScopeOwnershipDefaults() {
    }

    static void prepareInsert(String moduleAlias, DataScopeCapable entity) {
        if (entity == null) {
            return;
        }
        if (isBlank(entity.getAuthModuleAlias())) {
            entity.setAuthModuleAlias(moduleAlias);
        }
        CurrentUser currentUser = CurrentUserContext.currentUser().orElse(null);
        BusinessPrincipal principal = actingPrincipal(moduleAlias, currentUser);
        if (principal != null) {
            applyPrincipal(entity, principal);
            return;
        }
        applyCurrentUser(entity, currentUser);
    }

    private static BusinessPrincipal actingPrincipal(String moduleAlias, CurrentUser currentUser) {
        ActingContext actingContext = currentActionCode(moduleAlias)
                .flatMap(actionCode -> ActingContextHolder.current()
                        .filter(acting -> acting.matches(moduleAlias, actionCode)))
                .orElse(null);
        if (actingContext == null) {
            return null;
        }
        if (currentUser != null && !currentUser.userId().equals(actingContext.operator().userId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.data-scope-ownership-defaults.acting-context-operator-does-not-match-current-user",
                    "acting context operator does not match current user"));
        }
        return actingContext.principal();
    }

    private static Optional<String> currentActionCode(String moduleAlias) {
        return ActionExecutionContextHolder.current()
                .filter(context -> context.moduleAlias().equals(moduleAlias))
                .map(ActionExecutionContext::actionCode);
    }

    private static void applyPrincipal(DataScopeCapable entity, BusinessPrincipal principal) {
        if (isBlank(entity.getAuthUserId()) && !isBlank(principal.userId())) {
            entity.setAuthUserId(principal.userId());
        }
        if (isBlank(entity.getAuthOrganizationId()) && !isBlank(principal.organizationId())) {
            entity.setAuthOrganizationId(principal.organizationId());
        }
        if (isBlank(entity.getAuthDepartmentId()) && !isBlank(principal.departmentId())) {
            entity.setAuthDepartmentId(principal.departmentId());
        }
    }

    private static void applyCurrentUser(DataScopeCapable entity, CurrentUser currentUser) {
        if (currentUser == null) {
            return;
        }
        if (isBlank(entity.getAuthUserId())) {
            entity.setAuthUserId(currentUser.userId());
        }
        if (isBlank(entity.getAuthOrganizationId()) && !isBlank(currentUser.organizationId())) {
            entity.setAuthOrganizationId(currentUser.organizationId());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
