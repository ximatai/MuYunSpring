package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.util.List;

/** Adds request-derived business scope to the standard tree endpoint projection. */
public interface ScopedTreeWebProjectionPolicy<T extends EntityContract & TreeCapable, S extends TreeAbility<T>>
        extends TreeWebProjectionPolicy<T, S> {
    String TREE_SCOPE_ATTRIBUTE = ScopedTreeWebProjectionPolicy.class.getName() + ".treeScope";

    TreeScope treeScope(HttpServletRequest request);

    default TreeScope treeScope(HttpServletRequest request, T record) {
        return treeScope(request);
    }

    default TreeScope treeScopeForRecordLookup(HttpServletRequest request, String id) {
        return currentTreeScope(request) == null ? treeScope(request) : currentTreeScope(request);
    }

    @Override
    default T treeSelect(HttpServletRequest request, String id) {
        T record = scopedTreeSelect(PlatformAction.TREE, treeScopeForRecordLookup(request, id), id);
        if (record != null) {
            bindTreeScope(request, treeScope(request, record));
        }
        return record;
    }

    @Override
    default List<T> treeChildren(HttpServletRequest request, String parentId) {
        return treeChildrenForAction(request, PlatformAction.TREE, parentId);
    }

    @Override
    default List<T> treeChildrenForAction(HttpServletRequest request, PlatformAction action, String parentId) {
        return scopedTreeChildren(action, requireCurrentOrRequestTreeScope(request), parentId);
    }

    @Override
    default T treeSortSelect(HttpServletRequest request, String id) {
        T record = scopedTreeSelect(PlatformAction.SORT, treeScopeForRecordLookup(request, id), id);
        if (record != null) {
            bindTreeScope(request, treeScope(request, record));
        }
        return record;
    }

    @Override
    default List<T> treeSortChildren(HttpServletRequest request, String parentId) {
        return scopedTreeChildren(PlatformAction.SORT, requireCurrentOrRequestTreeScope(request), parentId);
    }

    @Override
    default void moveTree(HttpServletRequest request, String id, TreeSortWebRequest sortRequest) {
        TreeScope scope = requireCurrentOrRequestTreeScope(request);
        if (scope.tenantId() == null) {
            service().moveInTree(scope.criteria(), id,
                    sortRequest.previousId(), sortRequest.nextId(), sortRequest.parentId());
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.use(scope.tenantId())) {
            service().moveInTree(scope.criteria(), id,
                    sortRequest.previousId(), sortRequest.nextId(), sortRequest.parentId());
        }
    }

    @SuppressWarnings("unchecked")
    private T scopedTreeSelect(PlatformAction action, TreeScope scope, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Criteria criteria = scopedCriteria(scope).eq(StandardEntitySchema.ID_FIELD, id);
        if (service() instanceof DataScopeAbility<?> dataScopeAbility) {
            List<?> records = DataScopeAbility.cast(dataScopeAbility)
                    .listForAction(action, criteria, PageRequest.of(1, 1));
            return records.isEmpty() ? null : (T) records.getFirst();
        }
        return service().selectInScope(scope.criteria(), id);
    }

    @SuppressWarnings("unchecked")
    private List<T> scopedTreeChildren(PlatformAction action, TreeScope scope, String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return List.of();
        }
        if (!TreeAbility.ROOT_ID.equals(parentId) && scopedTreeSelect(action, scope, parentId) == null) {
            return List.of();
        }
        Criteria criteria = service().scopedTreeCriteria(scope.criteria(), parentId);
        if (service() instanceof DataScopeAbility<?> dataScopeAbility) {
            List<?> records = DataScopeAbility.cast(dataScopeAbility)
                    .listForAction(action, criteria, PageRequest.of(1, Integer.MAX_VALUE),
                            Sort.asc(PlatformAbilityFields.SORT_FIELD));
            return (List<T>) records;
        }
        return service().children(scope.criteria(), parentId);
    }

    private Criteria scopedCriteria(TreeScope scope) {
        Criteria criteria = Criteria.of();
        if (scope != null && scope.criteria() != null && !scope.criteria().isEmpty()) {
            criteria.andGroup(scope.criteria().getRoot());
        }
        return criteria;
    }

    private TreeScope requireCurrentOrRequestTreeScope(HttpServletRequest request) {
        TreeScope scope = currentTreeScope(request);
        if (scope != null) {
            return scope;
        }
        scope = treeScope(request);
        bindTreeScope(request, scope);
        return scope;
    }

    private TreeScope currentTreeScope(HttpServletRequest request) {
        Object value = request.getAttribute(TREE_SCOPE_ATTRIBUTE);
        return value instanceof TreeScope scope ? scope : null;
    }

    private void bindTreeScope(HttpServletRequest request, TreeScope scope) {
        request.setAttribute(TREE_SCOPE_ATTRIBUTE, scope == null ? TreeScope.none() : scope);
    }
}
