package net.ximatai.muyun.spring.ability.deletion;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import java.util.List;

/** Multiple observers share the same explicit deletion tree; at most one owns journal node IDs. */
public final class CompositeDeletionLifecycleListener implements DeletionLifecycleListener {
    private final List<DeletionLifecycleListener> listeners;
    public CompositeDeletionLifecycleListener(List<DeletionLifecycleListener> listeners) { this.listeners = List.copyOf(listeners); }
    @Override public DeletionLifecycleSession open(DeletionResource root) {
        var sessions = listeners.stream().map(listener -> listener.open(root)).toList();
        return new DeletionLifecycleSession() {
            @Override public DeletionNode started(CrudAbility<?> ability, EntityContract entity, DeletionContext context, DeletionMode mode) {
                DeletionNode node = DeletionNode.transientNode(new DeletionResource(ability.getModuleAlias(), entity.getId()));
                for (var session : sessions) {
                    var candidate = session.started(ability, entity, context, mode);
                    if (candidate.entryId() == null) continue;
                    if (node.entryId() != null) throw new IllegalStateException("Only one deletion observer may own journal nodes");
                    node = candidate;
                }
                return node;
            }
            @Override public void succeeded(CrudAbility<?> ability, EntityContract entity, DeletionContext context, DeletionNode node, DeletionMode mode) {
                sessions.forEach(session -> session.succeeded(ability, entity, context, node, mode));
            }
            @Override public void failed(CrudAbility<?> ability, EntityContract entity, DeletionContext context, DeletionNode node, DeletionMode mode, RuntimeException failure) {
                for (int index = sessions.size() - 1; index >= 0; index--) {
                    try { sessions.get(index).failed(ability, entity, context, node, mode, failure); }
                    catch (RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                }
            }
        };
    }
}
