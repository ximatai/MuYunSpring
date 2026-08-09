package net.ximatai.muyun.spring.ability.child;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class ChildRelation<C extends EntityContract, P extends EntityContract> {
    private final String relationCode;
    private final ChildAbility<C> childAbility;
    private final BiConsumer<C, String> setParentId;
    private final String childForeignKeyField;
    private final Function<P, List<C>> extractChildren;
    private boolean cascadeOnParentUnavailable;
    private BiConsumer<P, List<C>> populateChildren;

    public ChildRelation(ChildAbility<C> childAbility,
                         BiConsumer<C, String> setParentId,
                         String childForeignKeyField,
                         Function<P, List<C>> extractChildren) {
        this(null, childAbility, setParentId, childForeignKeyField, extractChildren);
    }

    public ChildRelation(String relationCode,
                         ChildAbility<C> childAbility,
                         BiConsumer<C, String> setParentId,
                         String childForeignKeyField,
                         Function<P, List<C>> extractChildren) {
        this.relationCode = relationCode == null || relationCode.isBlank() ? null : relationCode.trim();
        this.childAbility = childAbility;
        this.setParentId = setParentId;
        this.childForeignKeyField = childForeignKeyField;
        this.extractChildren = extractChildren;
    }

    public String relationCode() {
        return relationCode;
    }

    public ChildAbility<C> childAbility() {
        return childAbility;
    }

    public List<C> incomingChildren(P parent) {
        return extractChildren.apply(parent);
    }

    public ChildRelation<C, P> autoPopulate(BiConsumer<P, List<C>> value) {
        this.populateChildren = value;
        return this;
    }

    public ChildRelation<C, P> cascadeOnParentUnavailable() {
        this.cascadeOnParentUnavailable = true;
        return this;
    }

    public boolean isCascadeOnParentUnavailable() {
        return cascadeOnParentUnavailable;
    }

    public boolean isAutoPopulate() {
        return populateChildren != null;
    }

    public void loadChildren(P parent) {
        if (!isAutoPopulate() || parent == null || parent.getId() == null) {
            return;
        }
        populateChildren.accept(parent, selectChildren(parent.getId()));
    }

    public List<C> selectChildren(String parentId) {
        return childAbility.selectChildRows(Criteria.of().eq(childForeignKeyField, parentId));
    }

    public void insertChildren(String parentId, P parent) {
        List<C> children = extractChildren.apply(parent);
        if (children == null || children.isEmpty()) {
            return;
        }
        validateIncomingChildren(parentId, children, List.of());
        for (C child : children) {
            setParentId.accept(child, parentId);
        }
        childAbility.insertBatch(children);
    }

    public void replaceChildren(String parentId, P parent) {
        List<C> children = extractChildren.apply(parent);
        if (children == null) {
            return;
        }
        List<C> existing = selectChildren(parentId);
        validateIncomingChildren(parentId, children, existing);
        List<String> remainingIds = new ArrayList<>(existing.stream().map(EntityContract::getId).toList());
        for (C child : children) {
            setParentId.accept(child, parentId);
            if (child.getId() == null || child.getId().isBlank()) {
                childAbility.insert(child);
            } else if (remainingIds.remove(child.getId())) {
                childAbility.update(child);
            } else {
                childAbility.insert(child);
            }
        }
        deleteChildren(remainingIds);
    }

    public void clearChildren(String parentId) {
        deleteChildren(selectChildren(parentId).stream().map(EntityContract::getId).toList());
    }

    public void clearChildren(String parentId, DeletionContext deletionContext, DeletionNode deletionNode) {
        deleteChildren(selectChildren(parentId).stream().map(EntityContract::getId).toList(), deletionContext, deletionNode);
    }

    private void validateIncomingChildren(String parentId, List<C> children, List<C> existing) {
        Set<String> existingIds = new HashSet<>(existing.stream().map(EntityContract::getId).toList());
        Set<String> incomingIds = new LinkedHashSet<>();
        for (C child : children) {
            String childId = child.getId();
            if (childId == null || childId.isBlank()) {
                continue;
            }
            if (!incomingIds.add(childId)) {
                throw new PlatformException("Duplicate child id in relation payload: " + childId);
            }
            if (existingIds.contains(childId)) {
                continue;
            }
            C loaded = childAbility.getDao().findById(childId);
            if (loaded != null) {
                throw new PlatformException("Child record does not belong to parent " + parentId + ": " + childId);
            }
        }
    }

    private void deleteChildren(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        childAbility.deleteBatch(ids);
    }

    private void deleteChildren(List<String> ids, DeletionContext deletionContext, DeletionNode deletionNode) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            childAbility.delete(id, null, deletionContext.child(deletionNode, childAbility.getModuleAlias(), id));
        }
    }
}
