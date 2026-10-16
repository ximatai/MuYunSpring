package net.ximatai.muyun.spring.ability;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public interface TreeAbility<T extends TreeCapable> extends SortAbility<T> {
    String ROOT_ID = "root";

    default List<T> children(String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return List.of();
        }
        if (!ROOT_ID.equals(parentId) && selectActiveRaw(parentId) == null) {
            return List.of();
        }
        Criteria criteria = activeCriteria(Criteria.of().eq(PlatformAbilityFields.TREE_PARENT_FIELD, parentId));
        return getDao().query(criteria, PageRequests.all(), Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    /**
     * Resolves children for trees partitioned by a business scope, such as an application,
     * menu scheme, or dictionary category. The scope criteria is intentionally explicit so
     * services keep their business-shaped root methods while sharing tree mechanics.
     */
    default List<T> children(Criteria scopeCriteria, String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return List.of();
        }
        if (!ROOT_ID.equals(parentId) && selectInScope(scopeCriteria, parentId) == null) {
            return List.of();
        }
        Criteria criteria = scopedTreeCriteria(scopeCriteria, parentId);
        return getDao().query(activeCriteria(criteria), PageRequests.all(), Sort.asc(PlatformAbilityFields.SORT_FIELD));
    }

    /**
     * Builds the common sort/query scope for a tree node inside a business scope.
     */
    default Criteria scopedTreeCriteria(Criteria scopeCriteria, String parentId) {
        Criteria criteria = Criteria.of().eq(PlatformAbilityFields.TREE_PARENT_FIELD, parentId);
        if (scopeCriteria != null && !scopeCriteria.isEmpty()) {
            criteria.andGroup(scopeCriteria.getRoot());
        }
        return criteria;
    }

    default Criteria scopedTreeCriteria(T entity, String... fieldNames) {
        Criteria criteria = sortScopeByFields(entity, fieldNames);
        criteria.eq(PlatformAbilityFields.TREE_PARENT_FIELD, entity.getParentId());
        return criteria;
    }

    default List<T> rootChildrenInScope(Object scopeSource, String... fieldNames) {
        return children(BusinessScope.criteria(scopeSource, fieldNames), ROOT_ID);
    }

    default List<T> childrenInScope(Object scopeSource, String parentId, String... fieldNames) {
        return children(BusinessScope.criteria(scopeSource, fieldNames), parentId);
    }

    /**
     * Use from services that require a business scope to resolve root nodes.
     */
    default void rejectRootChildrenLookup(String scopedLookupName) {
        throw new PlatformException("Use " + scopedLookupName + " to resolve scoped root tree nodes");
    }

    @Override
    default SortPartition<T> sortPartition() {
        return SortPartitions.compose(
                SortPartitions.byFieldsWithMessage("Tree sort can only move records within the same parent",
                        PlatformAbilityFields.TREE_PARENT_FIELD),
                SortPartitions.fromModel(modelClass())
        );
    }

    default void validateTreeSortScopeByFields(T left, T right, String message, String... fieldNames) {
        validateSortScopeByFields(left, right, message, fieldNames);
        if (!SortAbility.sameValue(left.getParentId(), right.getParentId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.tree-sort-can-only-move-records-within-the-same-parent",
                    "Tree sort can only move records within the same parent"));
        }
    }

    default void moveInTree(String id, String previousId, String nextId, String parentId) {
        PlatformAbilityDispatcher.inMutationTransaction(() -> {
            moveInTreeInTransaction(id, previousId, nextId, parentId);
            return null;
        });
    }

    private void moveInTreeInTransaction(String id, String previousId, String nextId, String parentId) {
        T moving = select(id);
        if (moving == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.cannot-move-missing-tree-record",
                    "Cannot move missing tree record: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        rejectSelfNeighbor(id, previousId);
        rejectSelfNeighbor(id, nextId);
        String oldParentId = moving.getParentId();
        String targetParentId = resolveMoveParentId(moving, previousId, nextId, parentId);
        if (!SortAbility.sameValue(oldParentId, targetParentId)) {
            validateTreeMoveTarget(moving, targetParentId);
            moving.setParentId(targetParentId);
            validateTreePlacement(moving);
        }
        List<T> siblings = sortedTreeSiblings(moving, null);
        List<String> orderedIds = new ArrayList<>();
        for (T sibling : siblings) {
            if (!sibling.getId().equals(id)) {
                orderedIds.add(sibling.getId());
            }
        }
        T previous = previousId == null || previousId.isBlank() ? lastSibling(orderedIds) : select(previousId);
        T next = nextId == null || nextId.isBlank() ? null : select(nextId);
        validateTreeMoveSortScope(moving, previous, next);
        if (!SortAbility.sameValue(oldParentId, targetParentId)) {
            update(moving);
        }
        if (moveBetween(moving, previous, next)) {
            return;
        }
        int insertIndex = resolveInsertIndex(orderedIds, previousId, nextId);
        orderedIds.add(insertIndex, id);
        reorder(orderedIds);
    }

    default void moveInTree(Criteria scopeCriteria, String id, String previousId, String nextId, String parentId) {
        PlatformAbilityDispatcher.inMutationTransaction(() -> {
            moveInTreeInTransaction(scopeCriteria, id, previousId, nextId, parentId);
            return null;
        });
    }

    private void moveInTreeInTransaction(Criteria scopeCriteria, String id, String previousId, String nextId, String parentId) {
        T moving = selectInScope(scopeCriteria, id);
        if (moving == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.cannot-move-missing-tree-record",
                    "Cannot move missing tree record: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        rejectSelfNeighbor(id, previousId);
        rejectSelfNeighbor(id, nextId);
        String oldParentId = moving.getParentId();
        String targetParentId = resolveMoveParentId(scopeCriteria, moving, previousId, nextId, parentId);
        if (!SortAbility.sameValue(oldParentId, targetParentId)) {
            validateTreeMoveTarget(moving, targetParentId);
            moving.setParentId(targetParentId);
            validateTreePlacementInScopeOrThrow(moving, scopeCriteria, () -> PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.parent-scope",
                    "Tree parent must belong to the same scope")));
        }
        List<T> siblings = sortedTreeSiblings(moving, scopeCriteria);
        List<String> orderedIds = new ArrayList<>();
        for (T sibling : siblings) {
            if (!sibling.getId().equals(id)) {
                orderedIds.add(sibling.getId());
            }
        }
        T previous = previousId == null || previousId.isBlank() ? lastSibling(orderedIds) : selectInScope(scopeCriteria, previousId);
        T next = nextId == null || nextId.isBlank() ? null : selectInScope(scopeCriteria, nextId);
        validateTreeMoveSortScope(moving, previous, next);
        if (!SortAbility.sameValue(oldParentId, targetParentId)) {
            update(moving);
        }
        if (moveBetween(moving, previous, next)) {
            return;
        }
        int insertIndex = resolveInsertIndex(orderedIds, previousId, nextId);
        orderedIds.add(insertIndex, id);
        reorder(scopeCriteria, orderedIds);
    }

    private T lastSibling(List<String> orderedIds) {
        return orderedIds.isEmpty() ? null : select(orderedIds.getLast());
    }

    /**
     * Resolves the complete sibling sequence owned by the moving record's sort partition.
     * TreeAbility's public children methods intentionally remain business-shaped and may only
     * describe a parent; sorting must additionally honor the declared partition.
     */
    private List<T> sortedTreeSiblings(T moving, Criteria scopeCriteria) {
        Criteria criteria = sortScope(moving);
        if (scopeCriteria != null && !scopeCriteria.isEmpty()) {
            criteria.andGroup(scopeCriteria.getRoot());
        }
        return sortedList(criteria);
    }

    private void validateTreeMoveSortScope(T moving, T previous, T next) {
        if (previous != null) {
            validateSortScope(moving, previous);
        }
        if (next != null) {
            validateSortScope(moving, next);
        }
    }

    /**
     * Validates the business partition independently from the structural tree parent. Services
     * with a custom sort partition must override this hook; otherwise the model annotation is
     * used. Dynamic runtimes override the enclosing target hook because their fields come from
     * metadata rather than a Java model annotation.
     */
    default void validateTreeMoveBusinessPartition(T moving, T targetParent) {
        SortPartitions.fromModel(modelClass()).requireSamePartition(moving, targetParent);
    }

    default void validateTreeMoveTarget(T moving, String targetParentId) {
        if (targetParentId == null || targetParentId.isBlank() || ROOT_ID.equals(targetParentId)) {
            return;
        }
        T targetParent = select(targetParentId);
        if (targetParent != null) {
            validateTreeMoveBusinessPartition(moving, targetParent);
        }
    }

    private void rejectSelfNeighbor(String id, String neighborId) {
        if (neighborId != null && !neighborId.isBlank() && neighborId.equals(id)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.tree-move-neighbor-cannot-be-moving-record",
                    "Tree move neighbor cannot be moving record: {value1}"), Map.of("value1", String.valueOf(id)));
        }
    }

    private String resolveMoveParentId(T moving, String previousId, String nextId, String parentId) {
        String targetParentId = normalizeParentId(parentId);
        if (targetParentId == null) {
            targetParentId = neighborParentId(previousId);
        }
        if (targetParentId == null) {
            targetParentId = neighborParentId(nextId);
        }
        if (targetParentId == null) {
            targetParentId = normalizeParentId(moving.getParentId());
        }
        if (targetParentId == null) {
            targetParentId = ROOT_ID;
        }
        requireNeighborInParent(previousId, targetParentId);
        requireNeighborInParent(nextId, targetParentId);
        return targetParentId;
    }

    private String resolveMoveParentId(Criteria scopeCriteria, T moving, String previousId, String nextId, String parentId) {
        String targetParentId = normalizeParentId(parentId);
        if (targetParentId == null) {
            targetParentId = neighborParentId(scopeCriteria, previousId);
        }
        if (targetParentId == null) {
            targetParentId = neighborParentId(scopeCriteria, nextId);
        }
        if (targetParentId == null) {
            targetParentId = normalizeParentId(moving.getParentId());
        }
        if (targetParentId == null) {
            targetParentId = ROOT_ID;
        }
        requireNeighborInParent(scopeCriteria, previousId, targetParentId);
        requireNeighborInParent(scopeCriteria, nextId, targetParentId);
        return targetParentId;
    }

    private String normalizeParentId(String parentId) {
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    private String neighborParentId(String neighborId) {
        if (neighborId == null || neighborId.isBlank()) {
            return null;
        }
        T neighbor = select(neighborId);
        if (neighbor == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.cannot-move-relative-to-missing-tree-record",
                    "Cannot move relative to missing tree record: {value1}"), Map.of("value1", String.valueOf(neighborId)));
        }
        return normalizeParentId(neighbor.getParentId());
    }

    private String neighborParentId(Criteria scopeCriteria, String neighborId) {
        if (neighborId == null || neighborId.isBlank()) {
            return null;
        }
        T neighbor = selectInScope(scopeCriteria, neighborId);
        if (neighbor == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.cannot-move-relative-to-missing-tree-record-in-scope",
                    "Cannot move relative to missing tree record in scope: {value1}"), Map.of("value1", String.valueOf(neighborId)));
        }
        return normalizeParentId(neighbor.getParentId());
    }

    private void requireNeighborInParent(String neighborId, String parentId) {
        if (neighborId == null || neighborId.isBlank()) {
            return;
        }
        T neighbor = select(neighborId);
        if (neighbor == null || !SortAbility.sameValue(normalizeParentId(neighbor.getParentId()), parentId)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.tree-move-neighbor-must-belong-to-target-parent",
                    "Tree move neighbor must belong to target parent: {value1}"), Map.of("value1", String.valueOf(neighborId)));
        }
    }

    private void requireNeighborInParent(Criteria scopeCriteria, String neighborId, String parentId) {
        if (neighborId == null || neighborId.isBlank()) {
            return;
        }
        T neighbor = selectInScope(scopeCriteria, neighborId);
        if (neighbor == null || !SortAbility.sameValue(normalizeParentId(neighbor.getParentId()), parentId)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.tree-move-neighbor-must-belong-to-target-parent",
                    "Tree move neighbor must belong to target parent: {value1}"), Map.of("value1", String.valueOf(neighborId)));
        }
    }

    private int resolveInsertIndex(List<String> orderedIds, String previousId, String nextId) {
        if (previousId != null && !previousId.isBlank()) {
            int previousIndex = orderedIds.indexOf(previousId);
            if (previousIndex < 0) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.tree-ability.cannot-move-after-missing-previous-record",
                        "Cannot move after missing previous record: {value1}"), Map.of("value1", String.valueOf(previousId)));
            }
            return previousIndex + 1;
        }
        if (nextId != null && !nextId.isBlank()) {
            int nextIndex = orderedIds.indexOf(nextId);
            if (nextIndex < 0) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.tree-ability.cannot-move-before-missing-next-record",
                        "Cannot move before missing next record: {value1}"), Map.of("value1", String.valueOf(nextId)));
            }
            return nextIndex;
        }
        return orderedIds.size();
    }

    default List<String> ancestorIds(String id) {
        return resolveAncestorIds(id, false);
    }

    default List<String> ancestorIdsAndSelf(String id) {
        return resolveAncestorIds(id, true);
    }

    private List<String> resolveAncestorIds(String id, boolean includeSelf) {
        T current = selectActiveRaw(id);
        if (current == null) {
            return List.of();
        }
        List<String> ancestors = new ArrayList<>();
        if (includeSelf) {
            ancestors.add(current.getId());
        }
        Set<String> visited = new LinkedHashSet<>();
        visited.add(current.getId());
        String parentId = current.getParentId();
        while (parentId != null && !parentId.isBlank() && !ROOT_ID.equals(parentId)) {
            if (!visited.add(parentId)) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.tree-ability.tree-cycle-detected-while-resolving-ancestors",
                        "Tree cycle detected while resolving ancestors: {value1}"), Map.of("value1", String.valueOf(id)));
            }
            T parent = selectActiveRaw(parentId);
            if (parent == null) {
                break;
            }
            ancestors.add(0, parent.getId());
            parentId = parent.getParentId();
        }
        return ancestors;
    }

    default List<String> descendantIds(String id) {
        return descendantIds(Criteria.of(), id);
    }

    default List<String> descendantIds(Criteria scope, String id) {
        if (!ROOT_ID.equals(id) && selectInScope(scope, id) == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        collectDescendantIds(scope, id, result, new LinkedHashSet<>());
        return List.copyOf(result);
    }

    default List<String> selfAndDescendantIds(String id) {
        return selfAndDescendantIds(Criteria.of(), id);
    }

    default List<String> selfAndDescendantIds(Criteria scope, String id) {
        if (!ROOT_ID.equals(id) && selectInScope(scope, id) == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        ids.add(id);
        collectDescendantIds(scope, id, ids, new LinkedHashSet<>());
        return List.copyOf(ids);
    }

    default void validateTreePlacement(T entity) {
        validateTreePlacementBase(entity);
        String parentId = entity.getParentId();
        if (parentId != null && !parentId.isBlank() && !ROOT_ID.equals(parentId)) {
            validateTreeMoveBusinessPartition(entity, selectActiveRaw(parentId));
        }
    }

    default void validateTreePlacementBase(T entity) {
        String id = entity.getId();
        String parentId = entity.getParentId();
        if (parentId == null || parentId.isBlank() || ROOT_ID.equals(parentId)) {
            return;
        }
        if (parentId.equals(id)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.tree-node-cannot-use-itself-as-parent",
                    "Tree node cannot use itself as parent: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        T parent = selectActiveRaw(parentId);
        if (parent == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.tree-node-cannot-use-missing-parent",
                    "Tree node cannot use missing parent: {value1}"), Map.of("value1", String.valueOf(parentId)));
        }
        if (ancestorIds(parentId).contains(id)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.tree-node-cannot-move-under-its-descendant",
                    "Tree node cannot move under its descendant: {value1}"), Map.of("value1", String.valueOf(id)));
        }
    }

    /**
     * Validates normal tree placement and additionally requires the parent to be
     * visible in the same business scope as the incoming node.
     */
    default void validateTreePlacementInScope(T entity, Criteria scopeCriteria, String message) {
        validateTreePlacementInScopeOrThrow(entity, scopeCriteria, () -> new PlatformException(message));
    }

    /** Keeps scoped tree validation shared while the caller owns its rejection contract. */
    default void validateTreePlacementInScopeOrThrow(T entity, Criteria scopeCriteria,
                                                     java.util.function.Supplier<? extends RuntimeException> rejection) {
        String parentId = entity.getParentId();
        if (parentId == null || parentId.isBlank() || ROOT_ID.equals(parentId)) {
            return;
        }
        validateTreePlacementBase(entity);
        if (selectInScope(scopeCriteria, parentId) == null) {
            throw java.util.Objects.requireNonNull(rejection, "rejection").get();
        }
    }

    default T selectInScope(Criteria scopeCriteria, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Criteria criteria = Criteria.of().eq(StandardEntitySchema.ID_FIELD, id);
        if (scopeCriteria != null && !scopeCriteria.isEmpty()) {
            criteria.andGroup(scopeCriteria.getRoot());
        }
        return getDao().query(activeCriteria(criteria), new PageRequest(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void collectDescendantIds(Criteria scope, String parentId, List<String> result, Set<String> visited) {
        if (!visited.add(parentId)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.tree-ability.tree-cycle-detected-while-resolving-descendants",
                    "Tree cycle detected while resolving descendants: {value1}"), Map.of("value1", String.valueOf(parentId)));
        }
        for (T child : children(scope, parentId)) {
            if (visited.contains(child.getId())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.tree-ability.tree-cycle-detected-while-resolving-descendants",
                        "Tree cycle detected while resolving descendants: {value1}"), Map.of("value1", String.valueOf(parentId)));
            }
            result.add(child.getId());
            collectDescendantIds(scope, child.getId(), result, visited);
        }
        visited.remove(parentId);
    }
}
