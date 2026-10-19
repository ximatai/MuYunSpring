package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import java.util.Optional;

/** Read-only parent facts during standard aggregate child writes; nested aggregates restore the outer frame. */
public final class AggregateChildMutationContext implements AutoCloseable {
    private static final ThreadLocal<Parent> CURRENT = new ThreadLocal<>();
    public record Parent(CrudAbility<?> service, EntityContract before, EntityContract current) { }
    private final Parent previous;
    private AggregateChildMutationContext(CrudAbility<?> service, EntityContract before, EntityContract current) {
        previous = CURRENT.get(); CURRENT.set(new Parent(service, before, current));
    }
    public static AggregateChildMutationContext open(CrudAbility<?> service, EntityContract before, EntityContract current) {
        return new AggregateChildMutationContext(service, before, current);
    }
    public static Optional<Parent> parentOf(CrudAbility<?> child) {
        Parent parent = CURRENT.get();
        if (parent == null || !(parent.service() instanceof ChildrenAbility<?> owner)) return Optional.empty();
        boolean declared = owner.childRelations().stream().map(ChildRelation::childAbility).anyMatch(service ->
                service.getModuleAlias().equals(child.getModuleAlias()) && service.modelClass().equals(child.modelClass()));
        return declared ? Optional.of(parent) : Optional.empty();
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
