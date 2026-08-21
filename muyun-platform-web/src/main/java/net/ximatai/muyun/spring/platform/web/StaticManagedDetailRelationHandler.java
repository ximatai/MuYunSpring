package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationParentConstraint;

/**
 * Static-source adapter for one executable direct relation.
 *
 * <p>The declaration compiler owns whether a relation is readable or mutable.  This adapter owns
 * only the domain binding between a persisted parent and the child ability; it never receives a
 * browser supplied parent alias or binding expression.</p>
 */
public interface StaticManagedDetailRelationHandler<P extends EntityContract, C extends EntityContract> {
    String parentModuleAlias();

    String relationCode();

    /** The declared child field that the server binds from the persisted parent. */
    String parentBinding();

    String childEntityAlias();

    Class<C> childModelClass();

    CrudAbility<C> childService();

    Criteria criteriaFor(P parent);

    void bindParent(C child, P parent);

    boolean belongsTo(C child, P parent);

    default ResolvedDetailRelationParentConstraint parentConstraint() { return null; }

    default boolean availableFor(P parent) { return true; }
}
