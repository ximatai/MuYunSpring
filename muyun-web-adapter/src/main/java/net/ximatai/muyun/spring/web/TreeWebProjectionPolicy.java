package net.ximatai.muyun.spring.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Web-only policy hooks used by the standard tree endpoint projection. */
public interface TreeWebProjectionPolicy<T extends EntityContract & TreeCapable, S extends TreeAbility<T>>
        extends ScopedWeb<S> {

    /** Binds the scope-only sort context after the concrete tree projection has opted in. */
    static void bindTreeSortScope(HttpServletRequest request, String moduleAlias,
                                  TreeSortScopeRequest scope,
                                  NavigatorReferenceQueryContextResolver resolver,
                                  boolean supported) {
        if (scope == null) return;
        if (!supported) {
            throw new IllegalArgumentException("tree sort scope unsupported by tree projection");
        }
        if (scope.navigatorHostModuleAlias() != null && !scope.navigatorHostModuleAlias().isBlank()
                && resolver == null) {
            throw new IllegalArgumentException("navigator tree sort scope requires a context resolver");
        }
        WebQueryRequest query = scope.toQueryRequest();
        TreeWebQuerySupport.bind(request, resolver == null ? query
                : resolver.normalizeRequest(moduleAlias, query));
    }

    default void moveTree(HttpServletRequest request, String id, TreeSortWebRequest sortRequest) {
        service().moveInTree(id, sortRequest.previousId(), sortRequest.nextId(), sortRequest.parentId());
    }

    default T treeSelect(HttpServletRequest request, String id) {
        if (service() instanceof DataScopeAbility<?>) {
            DataScopeAbility<?> dataScopeAbility = DataScopeAbility.cast(service());
            @SuppressWarnings("unchecked")
            T record = (T) dataScopeAbility.selectForAction(PlatformAction.TREE, id);
            return record;
        }
        return service().select(id);
    }

    default List<T> treeChildren(HttpServletRequest request, String parentId) {
        return treeChildrenForAction(request, PlatformAction.TREE, parentId);
    }

    /**
     * Reads tree children under the supplied action while preserving the controller's
     * tree projection policy. Navigator references use this with REFERENCE rather
     * than bypassing scoped tree implementations.
     */
    default List<T> treeChildrenForAction(HttpServletRequest request, PlatformAction action, String parentId) {
        if (service() instanceof DataScopeAbility<?>) {
            DataScopeAbility<?> dataScopeAbility = DataScopeAbility.cast(service());
            @SuppressWarnings("unchecked")
            List<T> records = (List<T>) dataScopeAbility.childrenForAction(action, parentId);
            return records;
        }
        return service().children(parentId);
    }

    default T treeSortSelect(HttpServletRequest request, String id) {
        return service().select(id);
    }

    default List<T> treeSortChildren(HttpServletRequest request, String parentId) {
        return service().children(parentId);
    }

    default void requireTreeSortScope(HttpServletRequest request, String id, TreeSortWebRequest sortRequest) {
        if (!(service() instanceof DataScopeAbility<?> dataScopeAbility)) {
            return;
        }
        DataScopeAbility<?> dataScope = DataScopeAbility.cast(dataScopeAbility);
        Set<String> explicitIds = treeSortExplicitIds(id,
                sortRequest.previousId(), sortRequest.nextId(), sortRequest.parentId());
        DataScopeCriteriaResult scope = dataScope.requireRecordScopeResult(
                PlatformAction.SORT.executionPolicy(), explicitIds);
        Set<String> scopedIds = dataScope.withDataScopeTenant(scope,
                () -> treeSortScopeRecordIds(request, id,
                        sortRequest.previousId(), sortRequest.nextId(), sortRequest.parentId()));
        dataScope.requireRecordScopeResult(PlatformAction.SORT.executionPolicy(), scopedIds);
    }

    private Set<String> treeSortScopeRecordIds(HttpServletRequest request,
                                               String id,
                                               String previousId,
                                               String nextId,
                                               String parentId) {
        LinkedHashSet<String> recordIds = new LinkedHashSet<>(
                treeSortExplicitIds(id, previousId, nextId, parentId));
        T moving = treeSortSelect(request, id);
        if (moving == null) {
            return Set.copyOf(recordIds);
        }
        String targetParentId = normalizeParentId(parentId);
        if (targetParentId == null) targetParentId = neighborParentId(request, previousId);
        if (targetParentId == null) targetParentId = neighborParentId(request, nextId);
        if (targetParentId == null) targetParentId = normalizeParentId(moving.getParentId());
        if (targetParentId == null) targetParentId = TreeAbility.ROOT_ID;
        if (!TreeAbility.ROOT_ID.equals(targetParentId)) {
            recordIds.add(targetParentId);
        }
        treeSortChildren(request, targetParentId).stream()
                .map(EntityContract::getId)
                .forEach(recordIds::add);
        return Set.copyOf(recordIds);
    }

    private Set<String> treeSortExplicitIds(String id, String previousId, String nextId, String parentId) {
        LinkedHashSet<String> recordIds = new LinkedHashSet<>(normalizeIds(id, previousId, nextId));
        String normalizedParentId = normalizeParentId(parentId);
        if (normalizedParentId != null && !TreeAbility.ROOT_ID.equals(normalizedParentId)) {
            recordIds.add(normalizedParentId);
        }
        return Set.copyOf(recordIds);
    }

    private String neighborParentId(HttpServletRequest request, String neighborId) {
        if (neighborId == null || neighborId.isBlank()) {
            return null;
        }
        T neighbor = treeSortSelect(request, neighborId);
        return neighbor == null ? null : normalizeParentId(neighbor.getParentId());
    }

    private String normalizeParentId(String parentId) {
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    private Set<String> normalizeIds(String... ids) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        Arrays.stream(ids)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(normalized::add);
        return Set.copyOf(normalized);
    }
}
