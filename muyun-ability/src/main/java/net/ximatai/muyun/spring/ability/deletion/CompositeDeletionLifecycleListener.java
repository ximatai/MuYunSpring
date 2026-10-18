package net.ximatai.muyun.spring.ability.deletion;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import java.util.List;
import java.util.Objects;

/** Keeps the selected journal listener (including consumer overrides) and composes transactional observers. */
public final class CompositeDeletionLifecycleListener implements DeletionLifecycleListener {
    private final DeletionLifecycleListener listener;
    private final List<DeletionLifecycleObserver> observers;
    public CompositeDeletionLifecycleListener(DeletionLifecycleListener listener, List<DeletionLifecycleObserver> observers) {
        this.listener = Objects.requireNonNull(listener); this.observers = List.copyOf(observers);
    }
    @Override public void retainedRecordPurged(CrudAbility<?> ability, EntityContract entity) {
        observers.forEach(observer -> observer.retainedRecordPurged(ability, entity));
        listener.retainedRecordPurged(ability, entity);
    }
    @Override public DeletionLifecycleSession open(DeletionResource root) {
        var journal = listener.open(root);
        return new DeletionLifecycleSession() {
            @Override public DeletionNode started(CrudAbility<?> ability, EntityContract entity, DeletionContext context, DeletionMode mode) {
                return journal.started(ability, entity, context, mode);
            }
            @Override public void succeeded(CrudAbility<?> ability, EntityContract entity, DeletionContext context, DeletionNode node, DeletionMode mode) {
                observers.forEach(observer -> observer.succeeded(ability, entity, context, node, mode));
                journal.succeeded(ability, entity, context, node, mode);
            }
            @Override public void failed(CrudAbility<?> ability, EntityContract entity, DeletionContext context, DeletionNode node, DeletionMode mode, RuntimeException failure) {
                try { journal.failed(ability, entity, context, node, mode, failure); }
                catch (RuntimeException diagnosticFailure) { if (diagnosticFailure != failure) failure.addSuppressed(diagnosticFailure); }
            }
        };
    }
}
