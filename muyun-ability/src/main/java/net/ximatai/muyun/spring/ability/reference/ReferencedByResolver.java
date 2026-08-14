package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.Collection;

/** Resolves and populates inverse read associations declared on a static model. */
@FunctionalInterface
public interface ReferencedByResolver {
    ReferencedByResolver NONE = (ability, entity) -> { };

    void populate(CrudAbility<?> ability, EntityContract entity);

    /** Explicit batch counterpart for detail aggregators and future collection-aware list views. */
    default void populateAll(CrudAbility<?> ability, Collection<? extends EntityContract> entities) {
        if (entities != null) entities.forEach(entity -> populate(ability, entity));
    }
}
