package net.ximatai.muyun.spring.common.model;

import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.time.Instant;

public final class EntityLifecycle {
    private EntityLifecycle() {
    }

    public static void prepareInsert(EntityContract model, Instant now) {
        if (model.getId() == null || model.getId().isBlank()) {
            model.setId(Ids.newId());
        }
        TenantContext.applyToNewEntity(model);
        model.setVersion(model.getVersion() == null ? 0 : model.getVersion());
        model.setDeleted(Boolean.FALSE);
        model.setDeletedAt(null);
        model.setDeletedBy(null);
        String operatorId = currentOperatorId();
        if (operatorId != null && (model.getCreatedBy() == null || model.getCreatedBy().isBlank())) {
            model.setCreatedBy(operatorId);
        }
        if (operatorId != null) {
            model.setUpdatedBy(operatorId);
        }
        model.setCreatedAt(model.getCreatedAt() == null ? now : model.getCreatedAt());
        model.setUpdatedAt(now);
    }

    public static void prepareUpdate(EntityContract model, Instant now) {
        prepareUpdate(model, now, nextVersion(model.getVersion()));
    }

    public static void prepareUpdate(EntityContract model, Instant now, Integer nextVersion) {
        // Tenant-bound background work may only update records of its own tenant, never claim shared ones.
        if (TenantContext.isTenantSystem() && !TenantContext.matchesCurrentTenant(model)) {
            throw new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException(
                    "record tenant does not match the background job tenant");
        }
        model.setUpdatedAt(now);
        String operatorId = currentOperatorId();
        if (operatorId != null) {
            model.setUpdatedBy(operatorId);
        }
        model.setVersion(nextVersion);
    }

    public static void prepareDelete(EntityContract model, Instant now) {
        model.setDeleted(Boolean.TRUE);
        model.setDeletedAt(now);
        model.setDeletedBy(currentOperatorId());
        prepareUpdate(model, now);
    }

    /** Restores a previously soft-deleted record while preserving normal update auditing. */
    public static void prepareRestore(EntityContract model, Instant now) {
        model.setDeleted(Boolean.FALSE);
        model.setDeletedAt(null);
        model.setDeletedBy(null);
        prepareUpdate(model, now);
    }

    public static Integer nextVersion(Integer currentVersion) {
        return currentVersion == null ? 1 : currentVersion + 1;
    }

    private static String currentOperatorId() {
        return TenantContext.systemOperatorId()
                .orElseGet(() -> CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null));
    }
}
