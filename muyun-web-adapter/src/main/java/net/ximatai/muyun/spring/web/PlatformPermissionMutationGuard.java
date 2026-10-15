package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.model.capability.DataScopeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.action.ActionMessageType;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.i18n.TextReference;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal permissions never become an ordinary form's writable fields. */
public final class PlatformPermissionMutationGuard {
    private PlatformPermissionMutationGuard() { }
    public static void validate(EntityContract record, EntityContract existing) {
        if (!(record instanceof DataScopeCapable target)) return;
        DataScopeCapable old = existing instanceof DataScopeCapable scope ? scope : null;
        same(target.getAuthUserId(), old == null ? null : old.getAuthUserId());
        same(target.getAuthAssigneeIds(), old == null ? null : old.getAuthAssigneeIds());
        same(target.getAuthMemberIds(), old == null ? null : old.getAuthMemberIds());
        same(target.getAuthOrganizationId(), old == null ? null : old.getAuthOrganizationId());
        same(target.getAuthDepartmentId(), old == null ? null : old.getAuthDepartmentId());
        same(target.getAuthModuleAlias(), old == null ? null : old.getAuthModuleAlias());
        if (old != null) {
            target.setAuthUserId(old.getAuthUserId());
            target.setAuthAssigneeIds(old.getAuthAssigneeIds());
            target.setAuthMemberIds(old.getAuthMemberIds());
            target.setAuthOrganizationId(old.getAuthOrganizationId());
            target.setAuthDepartmentId(old.getAuthDepartmentId());
            target.setAuthModuleAlias(old.getAuthModuleAlias());
        }
    }
    public static PlatformException violation() {
        var source = TextReference.code(
                "platform.validation.permissions.action-required", "请通过授权动作变更权限");
        return new BusinessException(
                PlatformErrorCodes.VALIDATION_FAILED, 400,
                source.originalText(), ActionMessageType.WARNING,
                ErrorScope.empty(), List.of(),
                Map.of(), Map.of(), source);
    }

    private static void same(String supplied, String existing) {
        if (supplied != null && !Objects.equals(supplied, existing)) throw violation();
    }
}
