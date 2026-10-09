package net.ximatai.muyun.spring.ability.deletion;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** Validates domain lifecycle constraints before soft, hard or cascading record deletion. */
@FunctionalInterface
public interface RecordDeletionGuard {
    RecordDeletionGuard NONE = (ability, record) -> {};
    /** Called inside the mutation transaction, after parent locks and before deletion side effects. */
    void validate(CrudAbility<?> ability, EntityContract record);
}
