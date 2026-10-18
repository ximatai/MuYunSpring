package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import java.util.List;

/** Ordered cross-cutting observers share one save lifecycle; no observer replaces another. */
public final class CompositeEntitySaveLifecycleListener implements EntitySaveLifecycleListener {
    private final List<EntitySaveLifecycleListener> listeners;
    public CompositeEntitySaveLifecycleListener(List<EntitySaveLifecycleListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }
    @Override public <T extends EntityContract> void prepareValues(CrudAbility<T> ability, T existing, T incoming) {
        try { listeners.forEach(listener -> listener.prepareValues(ability, existing, incoming)); }
        catch (RuntimeException failure) { persistFailed(ability, incoming, failure); throw failure; }
    }
    @Override public <T extends EntityContract> void beforeSave(CrudAbility<T> ability, T existing, T incoming) {
        try { listeners.forEach(listener -> listener.beforeSave(ability, existing, incoming)); }
        catch (RuntimeException failure) { persistFailed(ability, incoming, failure); throw failure; }
    }
    @Override public <T extends EntityContract> void persisted(CrudAbility<T> ability, T entity) {
        listeners.forEach(listener -> listener.persisted(ability, entity));
    }
    @Override public <T extends EntityContract> void persistFailed(CrudAbility<T> ability, T entity, RuntimeException failure) {
        for (int index = listeners.size() - 1; index >= 0; index--) {
            try { listeners.get(index).persistFailed(ability, entity, failure); }
            catch (RuntimeException cleanupFailure) { if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure); }
        }
    }
}
