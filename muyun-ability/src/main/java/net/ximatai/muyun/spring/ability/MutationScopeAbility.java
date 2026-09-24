package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** Record ownership determines the required execution context, independently of business hooks. */
public interface MutationScopeAbility<T extends EntityContract> extends CrudAbility<T> {
    /** Receives the persisted record for existing mutations and the incoming draft for insertion. */
    void requireMutationContext(T record);
}
