package net.ximatai.muyun.spring.ability;

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
            throw new PlatformException("Tree sort can only move records within the same parent");
        }
    }

    default void moveInTree(String id, String previousId, String nextId, String parentId) {
        T moving = select(id);
        if (moving == null) {
            throw new PlatformException("Cannot move missing tree record: " + id);
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
        T moving = selectInScope(scopeCriteria, id);
        if (moving == null) {
            throw new PlatformException("Cannot move missing tree record: " + id);
        }
        rejectSelfNeighbor(id, previousId);
        rejectSelfNeighbor(id, nextId);
        String oldParentId = moving.getParentId();
        String targetParentId = resolveMoveParentId(scopeCriteria, moving, previousId, nextId, parentId);
        if (!SortAbility.sameValue(oldParentId, targetParentId)) {
            validateTreeMoveTarget(moving, targetParentId);
            moving.setParentId(targetParentId);
            validateTreePlacementInScope(moving, scopeCriteria, "Tree parent must belong to the same scope");
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
            throw new PlatformException("Tree move neighbor cannot be moving record: " + id);
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
            throw new PlatformException("Cannot move relative to missing tree record: " + neighborId);
        }
        return normalizeParentId(neighbor.getParentId());
    }

    private String neighborParentId(Criteria scopeCriteria, String neighborId) {
        if (neighborId == null || neighborId.isBlank()) {
            return null;
        }
        T neighbor = selectInScope(scopeCriteria, neighborId);
        if (neighbor == null) {
            throw new PlatformException("Cannot move relative to missing tree record in scope: " + neighborId);
        }
        return normalizeParentId(neighbor.getParentId());
    }

    private void requireNeighborInParent(String neighborId, String parentId) {
        if (neighborId == null || neighborId.isBlank()) {
            return;
        }
        T neighbor = select(neighborId);
        if (neighbor == null || !SortAbility.sameValue(normalizeParentId(neighbor.getParentId()), parentId)) {
            throw new PlatformException("Tree move neighbor must belong to target parent: " + neighborId);
        }
    }

    private void requireNeighborInParent(Criteria scopeCriteria, String neighborId, String parentId) {
        if (neighborId == null || neighborId.isBlank()) {
            return;
        }
        T neighbor = selectInScope(scopeCriteria, neighborId);
        if (neighbor == null || !SortAbility.sameValue(normalizeParentId(neighbor.getParentId()), parentId)) {
            throw new PlatformException("Tree move neighbor must belong to target parent: " + neighborId);
        }
    }

    private int resolveInsertIndex(List<String> orderedIds, String previousId, String nextId) {
        if (previousId != null && !previousId.isBlank()) {
            int previousIndex = orderedIds.indexOf(previousId);
            if (previousIndex < 0) {
                throw new PlatformException("Cannot move after missing previous record: " + previousId);
            }
            return previousIndex + 1;
        }
        if (nextId != null && !nextId.isBlank()) {
            int nextIndex = orderedIds.indexOf(nextId);
            if (nextIndex < 0) {
                throw new PlatformException("Cannot move before missing next record: " + nextId);
            }
            return nextIndex;
        }
        return orderedIds.size();
    }

    default List<String> ancestorIds(String id) {
        T current = select(id);
        if (current == null) {
            return List.of();
        }

        List<String> ancestors = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        String parentId = current.getParentId();
        while (parentId != null && !parentId.isBlank() && !ROOT_ID.equals(parentId)) {
            if (!visited.add(parentId)) {
                throw new PlatformException("Tree cycle detected while resolving ancestors: " + id);
            }
            T parent = select(parentId);
            if (parent == null) {
                break;
            }
            ancestors.add(0, parent.getId());
            parentId = parent.getParentId();
        }
        return ancestors;
    }

    default List<String> ancestorIdsAndSelf(String id) {
        if (select(id) == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(ancestorIds(id));
        ids.add(id);
        return ids;
    }

    default List<String> descendantIds(String id) {
        List<String> result = new ArrayList<>();
        collectDescendantIds(id, result, new LinkedHashSet<>());
        return result;
    }

    default List<String> selfAndDescendantIds(String id) {
        if (id == null || id.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        ids.add(id);
        ids.addAll(descendantIds(id));
        return List.copyOf(ids);
    }

    default void validateTreePlacement(T entity) {
        validateTreePlacementBase(entity);
    }

    default void validateTreePlacementBase(T entity) {
        String id = entity.getId();
        String parentId = entity.getParentId();
        if (parentId == null || parentId.isBlank() || ROOT_ID.equals(parentId)) {
            return;
        }
        if (parentId.equals(id)) {
            throw new PlatformException("Tree node cannot use itself as parent: " + id);
        }
        if (select(parentId) == null) {
            throw new PlatformException("Tree node cannot use missing parent: " + parentId);
        }
        if (ancestorIds(parentId).contains(id)) {
            throw new PlatformException("Tree node cannot move under its descendant: " + id);
        }
    }

    /**
     * Validates normal tree placement and additionally requires the parent to be
     * visible in the same business scope as the incoming node.
     */
    default void validateTreePlacementInScope(T entity, Criteria scopeCriteria, String message) {
        String parentId = entity.getParentId();
        if (parentId == null || parentId.isBlank() || ROOT_ID.equals(parentId)) {
            return;
        }
        validateTreePlacementBase(entity);
        if (selectInScope(scopeCriteria, parentId) == null) {
            throw new PlatformException(message);
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

    private void collectDescendantIds(String parentId, List<String> result, Set<String> visited) {
        if (!visited.add(parentId)) {
            throw new PlatformException("Tree cycle detected while resolving descendants: " + parentId);
        }
        for (T child : children(parentId)) {
            if (visited.contains(child.getId())) {
                throw new PlatformException("Tree cycle detected while resolving descendants: " + parentId);
            }
            result.add(child.getId());
            collectDescendantIds(child.getId(), result, visited);
        }
        visited.remove(parentId);
    }
}
