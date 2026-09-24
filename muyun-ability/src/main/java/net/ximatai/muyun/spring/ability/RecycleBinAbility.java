package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.ability.deletion.DeletionRecoveryAbility;

import java.util.List;
import java.util.function.Function;

/**
 * Optional lifecycle-management capability layered on top of soft deletion.
 *
 * <p>Soft deletion only retains a record. A service implements this ability
 * only when it deliberately exposes retained records to operators through a
 * recycle-bin lifecycle. Recovery execution and lifecycle audit remain
 * platform concerns.</p>
 */
public interface RecycleBinAbility<T extends EntityContract> extends SoftDeleteAbility<T>, DeletionRecoveryAbility<T> {
    /** Lists retained records visible to the current recycle-bin boundary. */
    default List<T> listRecycleBin(PageRequest pageRequest) {
        return pageRecycleBin(Criteria.of(), pageRequest).getRecords();
    }

    /** Executes recycle-bin reads with the same criteria, sorting and paging shape as a standard query. */
    default PageResult<T> pageRecycleBin(Criteria criteria, PageRequest pageRequest, Sort... sorts) {
        PageRequest effectivePage = pageRequest == null ? PageRequest.of(1, 20) : pageRequest;
        return withRecycleBinScope(PlatformAction.RECYCLE_BIN_QUERY.executionPolicy(),
                criteria, scoped -> getDao().pageQuery(
                        recycleBinReadCriteria(scoped), effectivePage, sorts));
    }

    /** Applies the single data-range fork used by both entity and projected recycle-bin queries. */
    default Criteria recycleBinReadCriteria(Criteria criteria) {
        beforeRecycleBinQuery();
        return recycleBinCriteria(criteria)
                .eq(StandardEntitySchema.DELETED_FIELD, Boolean.TRUE);
    }

    /**
     * Allows scoped services to add their normal recycle-bin visibility rules.
     * The default keeps the ordinary tenant filter, but intentionally does not
     * reuse {@link #activeCriteria(Criteria)} because active records and
     * retained records have opposite deleted-state predicates.
     */
    default Criteria recycleBinCriteria(Criteria criteria) {
        return tenantCriteria(criteria);
    }

    /** Hook for the resource owner to enforce its query boundary. */
    default void beforeRecycleBinQuery() {
    }

    /** Hook for the resource owner to enforce its recovery boundary. */
    default void beforeRecycleBinRestore() {
    }

    /** Checks whether the retained root record is visible to the current operator. */
    default boolean canAccessRecycleBinRecord(String id) {
        return canAccessRecycleBinRecord(PlatformAction.RECYCLE_BIN_QUERY.executionPolicy(), id);
    }

    default boolean canAccessRecycleBinRecord(ActionExecutionPolicy policy, String id) {
        if (policy == null || id == null || id.isBlank()) {
            return false;
        }
        beforeRecycleBinQuery();
        return withRecycleBinScope(policy, Criteria.of()
                .eq(StandardEntitySchema.ID_FIELD, id).eq(StandardEntitySchema.DELETED_FIELD, Boolean.TRUE),
                criteria -> !getDao().query(recycleBinCriteria(criteria), PageRequest.of(1, 1)).isEmpty());
    }

    /** Source ownership remains applicable after recovery changes the deleted state. */
    default boolean canAccessRecycleBinSourceRecord(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        beforeRecycleBinQuery();
        return withRecycleBinScope(PlatformAction.RECYCLE_BIN_QUERY.executionPolicy(),
                Criteria.of().eq(StandardEntitySchema.ID_FIELD, id),
                criteria -> !getDao().query(recycleBinCriteria(criteria), PageRequest.of(1, 1)).isEmpty());
    }

    private <R> R withRecycleBinScope(ActionExecutionPolicy policy, Criteria criteria, Function<Criteria, R> read) {
        if (this instanceof DataScopeAbility<?> scoped) {
            DataScopeCriteriaResult scope = scoped.readScopeByPolicy(policy, criteria);
            return scoped.withDataScopeTenant(scope, () -> read.apply(scope.criteria()));
        }
        return read.apply(criteria);
    }

    /**
     * Whether this resource has explicitly enabled irreversible cleanup.
     *
     * <p>This is deliberately separate from {@link #beforeRecycleBinPurge(String)} so delivery
     * surfaces can avoid advertising an operation which the resource does not own. Resource
     * implementations may still apply retention, authority and dependency checks in the hook.</p>
     */
    default boolean isRecycleBinPurgeEnabled() {
        return false;
    }

    /**
     * Physically removes one retained resource after its recycle-bin policy allows it.
     * This primitive deliberately does not infer or traverse children; the platform
     * purge coordinator owns source-tree validation, ordering and audit entries.
     */
    default int purge(String id) {
        return PlatformAbilityDispatcher.inMutationTransaction(() -> purgeInTransaction(id));
    }

    private int purgeInTransaction(String id) {
        if (id == null || id.isBlank()) {
            return 0;
        }
        if (!isRecycleBinPurgeEnabled()) {
            throw new UnsupportedOperationException("Recycle-bin purge is not enabled for " + getModuleAlias());
        }
        T entity = selectIgnoreSoftDelete(id);
        PlatformAbilityDispatcher.requireMutationContext(this, entity);
        beforeRecycleBinPurge(id);
        if (entity == null || !Boolean.TRUE.equals(entity.getDeleted())) {
            return 0;
        }
        int purged = getDao().deleteByIdAndVersion(id, entity.getVersion());
        if (purged <= 0) {
            throw new OptimisticLockException("record version conflict: " + id);
        }
        afterRecycleBinPurge(id, entity, purged);
        afterChanged(entity);
        CacheInvalidationSupport.clearAfterChanged(this, entity);
        return purged;
    }

    /** Defaults to deny: irreversible deletion requires an explicit business decision. */
    default void beforeRecycleBinPurge(String id) {
        throw new UnsupportedOperationException("Recycle-bin purge is not enabled for " + getModuleAlias());
    }

    default void afterRecycleBinPurge(String id, T entity, int purged) {
    }
}
