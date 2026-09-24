package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** Internal lifecycle executor used only after a recycle-bin entry or aggregate source has been verified. */
public final class RetainedRecordPurgeSupport {
    private RetainedRecordPurgeSupport() {
    }

    public static <T extends EntityContract> int purge(SoftDeleteAbility<T> ability,
                                                       String id, Integer expectedVersion) {
        return purge(ability, id, expectedVersion, () -> {});
    }

    static <T extends EntityContract> int purge(SoftDeleteAbility<T> ability, String id,
                                                 Integer expectedVersion, Runnable entryPolicy) {
        return PlatformAbilityDispatcher.inMutationTransaction(() ->
                purgeInTransaction(ability, id, expectedVersion, entryPolicy));
    }

    private static <T extends EntityContract> int purgeInTransaction(
            SoftDeleteAbility<T> ability, String id, Integer expectedVersion, Runnable entryPolicy) {
        if (id == null || id.isBlank()) {
            return 0;
        }
        T entity = ability.selectIgnoreSoftDelete(id);
        PlatformAbilityDispatcher.requireMutationContext(ability, entity);
        PlatformAbilityDispatcher.lockMutationParents(ability, entity, null);
        entryPolicy.run();
        ability.beforeRetainedRecordPurge(id);
        if (entity == null || !Boolean.TRUE.equals(entity.getDeleted())) {
            return 0;
        }
        if (expectedVersion != null && !expectedVersion.equals(entity.getVersion())) {
            throw new OptimisticLockException("record version conflict: " + id);
        }
        int purged = ability.getDao().deleteByIdAndVersion(id, entity.getVersion());
        if (purged <= 0) {
            throw new OptimisticLockException("record version conflict: " + id);
        }
        ability.afterRetainedRecordPurge(id, entity, purged);
        ability.afterChanged(entity);
        CacheInvalidationSupport.clearAfterChanged(ability, entity);
        return purged;
    }
}
