package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.capability.PlatformManagedCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.RecordActionAvailabilityDecision;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;

import java.util.Optional;
import java.util.Set;

/**
 * Protects platform-managed records from ordinary runtime mutation.
 */
public interface PlatformManagedProtectionAbility<T extends EntityContract & PlatformManagedCapable>
        extends CrudAbility<T> {

    default Set<String> editablePlatformManagedFields() {
        return Set.of(
                PlatformAbilityFields.ENABLED_FIELD,
                PlatformAbilityFields.SORT_FIELD
        );
    }

    default boolean allowOrdinaryPlatformManagedInsert(T entity) {
        return false;
    }

    /**
     * Projects the mutation boundary into record-action availability without changing the
     * service's own enable, disable, or sort rules. More specific business availability
     * contributors may provide a stronger decision before this default is applied.
     */
    default Optional<RecordActionAvailabilityDecision> ordinaryRecordActionAvailability(String actionCode,
                                                                                          T record) {
        if (record == null || !Boolean.TRUE.equals(record.getSystemManaged())) {
            return Optional.empty();
        }
        if (PlatformAction.UPDATE.matches(actionCode)) {
            return Optional.of(RecordActionAvailabilityDecision.unavailable("平台托管记录不可编辑"));
        }
        if (PlatformAction.DELETE.matches(actionCode)) {
            return Optional.of(RecordActionAvailabilityDecision.unavailable("平台托管记录不可删除"));
        }
        return Optional.empty();
    }
}
