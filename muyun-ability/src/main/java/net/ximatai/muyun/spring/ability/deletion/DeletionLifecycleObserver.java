package net.ximatai.muyun.spring.ability.deletion;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** Stateless transactional side effects; journal node identity remains owned by the selected listener. */
@FunctionalInterface
public interface DeletionLifecycleObserver {
    void succeeded(CrudAbility<?> ability, EntityContract entity, DeletionContext context,
                   DeletionNode node, DeletionMode mode);

    /** Final removal of a previously retained resource; the recovery coordinator owns its journal. */
    default void retainedRecordPurged(CrudAbility<?> ability, EntityContract entity) { }
}
