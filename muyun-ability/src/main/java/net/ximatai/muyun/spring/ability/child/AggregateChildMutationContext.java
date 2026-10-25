package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import java.util.Optional;

/** Read-only parent facts during standard aggregate child writes; nested aggregates restore the outer frame. */
public final class AggregateChildMutationContext implements AutoCloseable {
    private static final ThreadLocal<Parent> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Relation> RELATION = new ThreadLocal<>();
    public record Relation(String moduleAlias, String relationCode, EntityContract child) { }
    public record Parent(CrudAbility<?> service, EntityContract before, EntityContract current) { }
    private final Parent previous;
    private AggregateChildMutationContext(CrudAbility<?> service, EntityContract before, EntityContract current) {
        previous = CURRENT.get(); CURRENT.set(new Parent(service, before, current));
    }
    public static AggregateChildMutationContext open(CrudAbility<?> service, EntityContract before, EntityContract current) {
        return new AggregateChildMutationContext(service, before, current);
    }
    /** Declared relation provenance for a standalone child mutation; unrelated nested writes cannot inherit it. */
    public static RelationScope openRelation(String moduleAlias, String relationCode, EntityContract child) {
        return new RelationScope(new Relation(java.util.Objects.requireNonNull(moduleAlias),
                java.util.Objects.requireNonNull(relationCode), java.util.Objects.requireNonNull(child)));
    }
    public static Optional<Relation> relationOf(EntityContract child) {
        Relation relation = RELATION.get();
        return relation != null && relation.child() == child ? Optional.of(relation) : Optional.empty();
    }
    public static final class RelationScope implements AutoCloseable {
        private final Relation previous;
        private RelationScope(Relation relation) { previous = RELATION.get(); RELATION.set(relation); }
        @Override public void close() { if (previous == null) RELATION.remove(); else RELATION.set(previous); }
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
