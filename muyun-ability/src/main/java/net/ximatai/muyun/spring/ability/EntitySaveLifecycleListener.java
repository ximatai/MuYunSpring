package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** Cross-cutting lifecycle around the final validation and persistence of an entity. */
public interface EntitySaveLifecycleListener {
    EntitySaveLifecycleListener NONE = new EntitySaveLifecycleListener() {
    };

    /** Prepares platform-owned values before final validation; must not perform external write side effects. */
    default <T extends EntityContract> void prepareValues(CrudAbility<T> ability, T existing, T incoming) {
    }

    /** Runs after validation; may bind resources but must not change the validated record values. */
    default <T extends EntityContract> void beforeSave(CrudAbility<T> ability, T existing, T incoming) {
    }

    default <T extends EntityContract> void persisted(CrudAbility<T> ability, T entity) {
    }

    default <T extends EntityContract> void persistFailed(CrudAbility<T> ability, T entity, RuntimeException failure) {
    }
}
