package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.model.capability.EnabledCapable;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.PlatformAction;


public abstract class NestedEnabledTreeCrudWebSupport<
        T extends EntityContract & EnabledCapable & TreeCapable,
        S extends CrudAbility<T> & EnableAbility<T> & TreeAbility<T>>
        extends NestedCrudWebSupport<T, S>
        implements RecordWebProjectionPolicy, ScopedTreeWebProjectionPolicy<T, S> {

    @Override
    public void requireRecord(HttpServletRequest request, PlatformAction action, String id) {
        requireScopedRecord(request, id);
    }

    @Override
    public TreeScope treeScope(HttpServletRequest request) {
        return TreeScope.of(treeScopeCriteria(request));
    }

    protected Criteria treeScopeCriteria(HttpServletRequest request) {
        return Criteria.of();
    }

}
