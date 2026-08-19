package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.Collection;

/** Resolves typed {@link ReferenceLoad} paths after static or dynamic compilation. */
@FunctionalInterface
public interface ReferenceLoadResolver {
    ReferenceLoadResolver NONE = (ability, entity) -> { };

    void populate(CrudAbility<?> ability, EntityContract entity);

    /** Batch counterpart used by ordinary list reads; implementations must avoid per-record target queries. */
    default void populateAll(CrudAbility<?> ability, Collection<? extends EntityContract> entities) {
        if (entities == null) return;
        entities.forEach(entity -> populate(ability, entity));
    }

}
