package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.PageRequests;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

public interface ChildAbility<C extends EntityContract> extends CrudAbility<C> {
    /**
     * Optional domain identity hook for aggregate children whose former row is
     * soft-deleted. Returning that row lets the relation lifecycle restore and
     * update it instead of attempting a duplicate insert.
     */
    default C findDeletedReplacement(C incoming) {
        return null;
    }

    default boolean restoreDeletedReplacement(C incoming) {
        C deleted = findDeletedReplacement(incoming);
        if (deleted == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(deleted.getDeleted())) {
            throw new PlatformException("Child replacement must be soft-deleted: " + deleted.getId());
        }
        if (!(this instanceof SoftDeleteAbility<?> softDeleteAbility)) {
            throw new PlatformException("Child replacement requires soft-delete ability: " + getModuleAlias());
        }
        @SuppressWarnings("unchecked")
        SoftDeleteAbility<C> typed = (SoftDeleteAbility<C>) softDeleteAbility;
        typed.restore(deleted.getId(), deleted.getVersion());
        C restored = typed.selectIgnoreSoftDelete(deleted.getId());
        incoming.setId(restored.getId());
        incoming.setVersion(restored.getVersion());
        return true;
    }

    default <P extends EntityContract> ChildRelation<C, P> toChildRelation(BiConsumer<C, String> setParentId,
                                                                           String childForeignKeyField,
                                                                           Function<P, List<C>> extractChildren) {
        return new ChildRelation<>(this, setParentId, childForeignKeyField, extractChildren);
    }

    default <P extends EntityContract> ChildRelation<C, P> toChildRelation(ChildPlan plan,
                                                                           BiConsumer<C, String> setParentId,
                                                                           Function<P, List<C>> extractChildren,
                                                                           BiConsumer<P, List<C>> populateChildren) {
        ChildRelation<C, P> relation = new ChildRelation<>(plan.relationCode(), this, setParentId,
                plan.childForeignKeyField(), extractChildren);
        if (plan.autoPopulate()) {
            if (populateChildren == null) {
                throw new PlatformException("auto populate child relation requires populateChildren: " + plan.relationCode());
            }
            relation.autoPopulate(populateChildren);
        }
        if (plan.cascadeOnParentUnavailable()) {
            relation.cascadeOnParentUnavailable();
        }
        return relation;
    }

    default List<C> selectChildRows(Criteria criteria) {
        requireGenericChildReadWithoutIndependentDataScope();
        if (this instanceof SortAbility<?> sortAbility) {
            return sortedChildRows(sortAbility, criteria);
        }
        return getDao().query(activeCriteria(criteria), PageRequests.all());
    }

    /** Complete retained children for a parent-scoped, platform-declared recycle-bin view. */
    default List<C> selectDeletedChildRows(Criteria criteria) {
        requireGenericChildReadWithoutIndependentDataScope();
        if (!(this instanceof SoftDeleteAbility<?> softDeleteAbility)) {
            throw new PlatformException("Child recycle bin requires soft-delete ability: " + getModuleAlias());
        }
        @SuppressWarnings("unchecked")
        SoftDeleteAbility<C> typed = (SoftDeleteAbility<C>) softDeleteAbility;
        return getDao().query(typed.deletedCriteria(criteria), PageRequests.all());
    }

    default C selectIgnoreSoftDeleteIfPossible(String id) {
        if (this instanceof SoftDeleteAbility<?> softDeleteAbility) {
            @SuppressWarnings("unchecked")
            C selected = (C) softDeleteAbility.selectIgnoreSoftDelete(id);
            return selected;
        }
        return select(id);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<C> sortedChildRows(SortAbility<?> sortAbility, Criteria criteria) {
        return ((SortAbility) sortAbility).sortedList(criteria);
    }

    private void requireGenericChildReadWithoutIndependentDataScope() {
        if (this instanceof DataScopeAbility<?>) {
            throw new PlatformException("generic child reads do not support independent DataScopeAbility: "
                    + getModuleAlias() + ", use an explicitly scoped child reader");
        }
    }
}
