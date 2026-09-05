package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Executes dynamic record mutations after the facade has resolved its module/entity entry point.
 * Entity lifecycle, field protection and DAO behavior remain owned by {@link DynamicEntityService}.
 */
final class DynamicRecordMutationRuntime {
    private final DynamicRecordRuntime runtime;
    private final DynamicRecordEventPublisher eventPublisher;
    private final ActionExecutionPolicyService actionPolicy;
    private final DataScopeCriteriaService dataScope;
    private final DynamicRecordMutationCoordinator coordinator;
    private final Clock clock;

    DynamicRecordMutationRuntime(DynamicRecordRuntime runtime,
                                 DynamicRecordEventPublisher eventPublisher,
                                 ActionExecutionPolicyService actionPolicy,
                                 DataScopeCriteriaService dataScope,
                                 DynamicRecordMutationCoordinator coordinator,
                                 Clock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.actionPolicy = Objects.requireNonNull(actionPolicy, "actionPolicy must not be null");
        this.dataScope = Objects.requireNonNull(dataScope, "dataScope must not be null");
        this.coordinator = coordinator == null ? DynamicRecordMutationCoordinator.NONE : coordinator;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    String create(String moduleAlias, String entityAlias, DynamicRecord record,
                  RuntimeMutationSource source, String traceId, Map<String, Object> metadata) {
        try (DynamicMutationContext context = DynamicMutationContext.open(clock, source, traceId, metadata)) {
            if (source == RuntimeMutationSource.BUSINESS) {
                actionPolicy.requireAuthorized(ActionExecutionContext.ofPlatformAction(moduleAlias, PlatformAction.CREATE,
                        Set.of(), CurrentUserContext.currentUser()));
            }
            coordinator.beforeCreate(moduleAlias, entityAlias, record);
            List<ChildMutation> children = prepareChildrenForCreate(moduleAlias, entityAlias, record);
            children.forEach(item -> coordinator.beforeRelationChildCreate(moduleAlias, entityAlias,
                    item.relation.code(), item.relation.childEntityAlias(), record, item.incoming));
            String id = entityService(moduleAlias, entityAlias).insert(record);
            coordinator.afterCreate(moduleAlias, entityAlias, record, id);
            coordinator.afterMutation(event(DynamicRecordMutationEventType.AFTER_SAVE, moduleAlias, entityAlias, id,
                    DynamicRecordSaveOperation.CREATE, null, record, context));
            children.forEach(item -> coordinator.afterRelationChildCreate(moduleAlias, entityAlias,
                    item.relation.code(), item.relation.childEntityAlias(), record, item.incoming, item.incoming.getId()));
            eventPublisher.created(eventContext(moduleAlias, entityAlias, source, traceId), id);
            return id;
        }
    }

    int update(String moduleAlias, String entityAlias, DynamicRecord record,
               RuntimeMutationSource source, String traceId, Map<String, Object> metadata) {
        try (DynamicMutationContext context = DynamicMutationContext.open(clock, source, traceId, metadata)) {
            if (record == null) {
                throw new PlatformException("dynamic record must not be null");
            }
            DataScopeCriteriaResult scope = source == RuntimeMutationSource.BUSINESS
                    ? requireBusinessMutation(moduleAlias, entityAlias, PlatformAction.UPDATE, ids(record.getId()))
                    : DataScopeCriteriaResult.unrestricted(Criteria.of());
            DynamicRecord before = withTenantScope(scope,
                    () -> entityService(moduleAlias, entityAlias).selectActiveRaw(record.getId()));
            coordinator.beforeUpdate(moduleAlias, entityAlias, before, record);
            List<ChildMutation> children = prepareChildrenForUpdate(moduleAlias, entityAlias, before, record);
            beforeChildren(moduleAlias, entityAlias, before, record, children);
            int updated = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).update(record));
            if (updated > 0) {
                coordinator.afterUpdate(moduleAlias, entityAlias, before, record);
                coordinator.afterMutation(event(DynamicRecordMutationEventType.AFTER_SAVE, moduleAlias, entityAlias,
                        record.getId(), DynamicRecordSaveOperation.UPDATE, before, record, context));
                afterChildren(moduleAlias, entityAlias, before, record, children);
                eventPublisher.updated(eventContext(moduleAlias, entityAlias, source, traceId), record.getId());
            }
            return updated;
        }
    }

    int delete(String moduleAlias, String entityAlias, String id, Integer expectedVersion,
               RuntimeMutationSource source, String traceId) {
        try (DynamicMutationContext context = DynamicMutationContext.open(clock, source, traceId, Map.of())) {
            DataScopeCriteriaResult scope = source == RuntimeMutationSource.BUSINESS
                    ? requireBusinessMutation(moduleAlias, entityAlias, PlatformAction.DELETE, ids(id))
                    : DataScopeCriteriaResult.unrestricted(Criteria.of());
            DynamicRecord before = withTenantScope(scope,
                    () -> entityService(moduleAlias, entityAlias).selectActiveRaw(id));
            coordinator.beforeDelete(moduleAlias, entityAlias, before);
            List<ChildMutation> children = cascadeChildren(moduleAlias, entityAlias, before);
            children.forEach(item -> coordinator.beforeRelationChildDelete(moduleAlias, entityAlias,
                    item.relation.code(), item.relation.childEntityAlias(), before, item.before));
            int deleted = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).delete(id, expectedVersion));
            if (deleted > 0) {
                coordinator.afterDelete(moduleAlias, entityAlias, before);
                coordinator.afterMutation(event(DynamicRecordMutationEventType.AFTER_DELETE, moduleAlias, entityAlias,
                        id, null, before, null, context));
                children.forEach(item -> coordinator.afterRelationChildDelete(moduleAlias, entityAlias,
                        item.relation.code(), item.relation.childEntityAlias(), before, item.before));
                eventPublisher.deleted(eventContext(moduleAlias, entityAlias, source, traceId), id);
            }
            return deleted;
        }
    }

    int deleteBatch(String moduleAlias, String entityAlias, Collection<String> recordIds,
                    RuntimeMutationSource source, String traceId) {
        try (DynamicMutationContext context = DynamicMutationContext.open(clock, source, traceId, Map.of())) {
            Set<String> ids = ids(recordIds);
            if (ids.isEmpty()) {
                return 0;
            }
            DataScopeCriteriaResult scope = source == RuntimeMutationSource.BUSINESS
                    ? requireBusinessMutation(moduleAlias, entityAlias, PlatformAction.DELETE, ids)
                    : DataScopeCriteriaResult.unrestricted(Criteria.of());
            List<DynamicRecord> before = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias)
                    .list(Criteria.of().in("id", List.copyOf(ids)), PageRequest.of(1, ids.size())));
            List<ChildMutation> children = before.stream().flatMap(record -> cascadeChildren(moduleAlias, entityAlias, record)
                    .stream().map(item -> item.withParentBefore(record))).toList();
            before.forEach(record -> coordinator.beforeDelete(moduleAlias, entityAlias, record));
            children.forEach(item -> coordinator.beforeRelationChildDelete(moduleAlias, entityAlias,
                    item.relation.code(), item.relation.childEntityAlias(), item.parentBefore, item.before));
            int deleted = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).deleteBatch(ids));
            if (deleted > 0) {
                before.forEach(record -> {
                    coordinator.afterDelete(moduleAlias, entityAlias, record);
                    coordinator.afterMutation(event(DynamicRecordMutationEventType.AFTER_DELETE, moduleAlias, entityAlias,
                            record.getId(), null, record, null, context));
                });
                children.forEach(item -> coordinator.afterRelationChildDelete(moduleAlias, entityAlias,
                        item.relation.code(), item.relation.childEntityAlias(), item.parentBefore, item.before));
                eventPublisher.deletedBatch(eventContext(moduleAlias, entityAlias, source, traceId), List.copyOf(ids), deleted);
            }
            return deleted;
        }
    }

    String createWriteBack(String moduleAlias, String entityAlias, DynamicRecord record,
                           DynamicWriteBackContext writeBackContext, Map<String, Object> metadata) {
        try (DynamicMutationContext ignored = DynamicMutationContext.openWriteBack(clock, writeBackContext, metadata)) {
            return create(moduleAlias, entityAlias, record, RuntimeMutationSource.WRITE_BACK,
                    DynamicMutationContext.current().orElseThrow().traceId(), metadata);
        }
    }

    int updateWriteBack(String moduleAlias, String entityAlias, DynamicRecord record,
                        DynamicWriteBackContext writeBackContext, Map<String, Object> metadata) {
        try (DynamicMutationContext ignored = DynamicMutationContext.openWriteBack(clock, writeBackContext, metadata)) {
            return update(moduleAlias, entityAlias, record, RuntimeMutationSource.WRITE_BACK,
                    DynamicMutationContext.current().orElseThrow().traceId(), metadata);
        }
    }

    int enable(String moduleAlias, String entityAlias, String id, Integer expectedVersion,
               RuntimeMutationSource source, String traceId) {
        DataScopeCriteriaResult scope = source == RuntimeMutationSource.BUSINESS
                ? requireBusinessMutation(moduleAlias, entityAlias, PlatformAction.ENABLE, ids(id))
                : DataScopeCriteriaResult.unrestricted(Criteria.of());
        int updated = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).enable(id, expectedVersion));
        if (updated > 0) {
            eventPublisher.enabled(eventContext(moduleAlias, entityAlias, source, traceId), id);
        }
        return updated;
    }

    int disable(String moduleAlias, String entityAlias, String id, Integer expectedVersion,
                RuntimeMutationSource source, String traceId) {
        DataScopeCriteriaResult scope = source == RuntimeMutationSource.BUSINESS
                ? requireBusinessMutation(moduleAlias, entityAlias, PlatformAction.DISABLE, ids(id))
                : DataScopeCriteriaResult.unrestricted(Criteria.of());
        int updated = withTenantScope(scope, () -> entityService(moduleAlias, entityAlias).disable(id, expectedVersion));
        if (updated > 0) {
            eventPublisher.disabled(eventContext(moduleAlias, entityAlias, source, traceId), id);
        }
        return updated;
    }

    void reorder(String moduleAlias, String entityAlias, List<String> orderedIds,
                 RuntimeMutationSource source, String traceId) {
        Set<String> ids = ids(orderedIds);
        DataScopeCriteriaResult scope = requiresSortScope(source)
                ? sortMutationScope(moduleAlias, entityAlias, ids, Criteria.of(), () -> ids)
                : DataScopeCriteriaResult.unrestricted(Criteria.of());
        withTenantScope(scope, () -> {
            entityService(moduleAlias, entityAlias).reorder(orderedIds);
            return null;
        });
        eventPublisher.reordered(eventContext(moduleAlias, entityAlias, source, traceId), orderedIds);
    }

    void moveBefore(String moduleAlias, String entityAlias, String id, String beforeId,
                    RuntimeMutationSource source, String traceId) {
        DataScopeCriteriaResult scope = requiresSortScope(source)
                ? sortMutationScope(moduleAlias, entityAlias, ids(Arrays.asList(id, beforeId)), Criteria.of(),
                () -> sortScopeRecordIds(moduleAlias, entityAlias, id, beforeId))
                : DataScopeCriteriaResult.unrestricted(Criteria.of());
        withTenantScope(scope, () -> {
            entityService(moduleAlias, entityAlias).moveBefore(id, beforeId);
            return null;
        });
        eventPublisher.movedBefore(eventContext(moduleAlias, entityAlias, source, traceId), id, beforeId);
    }

    void moveAfter(String moduleAlias, String entityAlias, String id, String afterId,
                   RuntimeMutationSource source, String traceId) {
        DataScopeCriteriaResult scope = requiresSortScope(source)
                ? sortMutationScope(moduleAlias, entityAlias, ids(Arrays.asList(id, afterId)), Criteria.of(),
                () -> sortScopeRecordIds(moduleAlias, entityAlias, id, afterId))
                : DataScopeCriteriaResult.unrestricted(Criteria.of());
        withTenantScope(scope, () -> {
            entityService(moduleAlias, entityAlias).moveAfter(id, afterId);
            return null;
        });
        eventPublisher.movedAfter(eventContext(moduleAlias, entityAlias, source, traceId), id, afterId);
    }

    void moveInTree(String moduleAlias, String entityAlias, String id, String previousId, String nextId,
                    String parentId, RuntimeMutationSource source, String traceId) {
        moveInTree(moduleAlias, entityAlias, id, previousId, nextId, parentId, Criteria.of(), source, traceId);
    }

    void moveInTree(String moduleAlias, String entityAlias, String id, String previousId, String nextId,
                    String parentId, Criteria sortScope, RuntimeMutationSource source, String traceId) {
        DataScopeCriteriaResult scope = requiresSortScope(source)
                ? sortMutationScope(moduleAlias, entityAlias, treeExplicitIds(id, previousId, nextId, parentId), sortScope,
                () -> treeSortScopeRecordIds(moduleAlias, entityAlias, id, previousId, nextId, parentId, sortScope))
                : DataScopeCriteriaResult.unrestricted(Criteria.of());
        withTenantScope(scope, () -> {
            entityService(moduleAlias, entityAlias).moveInTree(sortScope, id, previousId, nextId, parentId);
            return null;
        });
        eventPublisher.movedInTree(eventContext(moduleAlias, entityAlias, source, traceId),
                id, previousId, nextId, parentId);
    }

    private void beforeChildren(String module, String entity, DynamicRecord before, DynamicRecord incoming, List<ChildMutation> children) {
        children.forEach(item -> {
            if (item.kind == Kind.CREATE) coordinator.beforeRelationChildCreate(module, entity, item.relation.code(), item.relation.childEntityAlias(), before, item.incoming);
            else if (item.kind == Kind.UPDATE) coordinator.beforeRelationChildUpdate(module, entity, item.relation.code(), item.relation.childEntityAlias(), before, incoming, item.before, item.incoming);
            else coordinator.beforeRelationChildDelete(module, entity, item.relation.code(), item.relation.childEntityAlias(), before, item.before);
        });
    }

    private void afterChildren(String module, String entity, DynamicRecord before, DynamicRecord updated, List<ChildMutation> children) {
        children.forEach(item -> {
            if (item.kind == Kind.CREATE) coordinator.afterRelationChildCreate(module, entity, item.relation.code(), item.relation.childEntityAlias(), updated, item.incoming, item.incoming.getId());
            else if (item.kind == Kind.UPDATE) coordinator.afterRelationChildUpdate(module, entity, item.relation.code(), item.relation.childEntityAlias(), before, updated, item.before, item.incoming);
            else coordinator.afterRelationChildDelete(module, entity, item.relation.code(), item.relation.childEntityAlias(), before, item.before);
        });
    }

    private List<ChildMutation> prepareChildrenForCreate(String module, String entity, DynamicRecord parent) {
        if (parent == null || parent.getChildren().isEmpty()) return List.of();
        ensureId(parent);
        List<ChildMutation> result = new ArrayList<>();
        for (DynamicRelationDescriptor relation : childRelations(module, entity)) {
            List<DynamicRecord> children = parent.getChildren(relation.code());
            if (children == null) continue;
            for (DynamicRecord child : children) { prepareChild(parent, relation, child); result.add(ChildMutation.create(relation, child)); }
        }
        return List.copyOf(result);
    }

    private List<ChildMutation> prepareChildrenForUpdate(String module, String entity, DynamicRecord before, DynamicRecord incoming) {
        if (incoming == null || incoming.getChildren().isEmpty()) return List.of();
        List<ChildMutation> result = new ArrayList<>();
        for (DynamicRelationDescriptor relation : childRelations(module, entity)) {
            if (!incoming.getChildren().containsKey(relation.code()) || incoming.getChildren(relation.code()) == null) continue;
            Map<String, DynamicRecord> old = childrenById(module, relation, incoming.getId());
            for (DynamicRecord child : incoming.getChildren(relation.code())) {
                prepareChild(before == null ? incoming : before, relation, child);
                DynamicRecord oldChild = old.remove(child.getId());
                result.add(oldChild == null ? ChildMutation.create(relation, child) : ChildMutation.update(relation, oldChild, child));
            }
            if (!incoming.isPartialChildren(relation.code())) old.values().forEach(child -> result.add(ChildMutation.delete(relation, child)));
        }
        return List.copyOf(result);
    }

    private List<ChildMutation> cascadeChildren(String module, String entity, DynamicRecord before) {
        if (before == null || before.getId() == null || before.getId().isBlank()) return List.of();
        List<ChildMutation> result = new ArrayList<>();
        childRelations(module, entity).stream().filter(DynamicRelationDescriptor::cascadeOnParentUnavailable)
                .forEach(relation -> childrenById(module, relation, before.getId()).values()
                        .forEach(child -> result.add(ChildMutation.delete(relation, child))));
        return List.copyOf(result);
    }

    private List<DynamicRelationDescriptor> childRelations(String module, String entity) {
        return runtime.describe(module).relations().stream().filter(item -> entity.equals(item.parentEntityAlias())).toList();
    }

    private Map<String, DynamicRecord> childrenById(String module, DynamicRelationDescriptor relation, String parentId) {
        if (parentId == null || parentId.isBlank()) return Map.of();
        LinkedHashMap<String, DynamicRecord> result = new LinkedHashMap<>();
        entityService(module, relation.childEntityAlias()).selectChildRows(Criteria.of().eq(relation.childForeignKeyField(), parentId))
                .forEach(child -> { if (child.getId() != null && !child.getId().isBlank()) result.put(child.getId(), child); });
        return result;
    }

    private void prepareChild(DynamicRecord parent, DynamicRelationDescriptor relation, DynamicRecord child) {
        ensureId(child); child.putPlatformValue(relation.childForeignKeyField(), parent.getId());
        if (child.getTenantId() == null && parent.getTenantId() != null) child.setTenantId(parent.getTenantId());
    }

    private DataScopeCriteriaResult requireBusinessMutation(String module, String entity, PlatformAction action, Set<String> recordIds) {
        actionPolicy.requireRecordAction(ActionExecutionContext.ofPlatformAction(module, action, recordIds, CurrentUserContext.currentUser()));
        if (!supportsCapability(module, entity, EntityCapability.DATA_SCOPE)) return DataScopeCriteriaResult.unrestricted(Criteria.of());
        if (recordIds.isEmpty()) throw new IllegalArgumentException("record action requires record ids: " + module + "." + action.code());
        var policy = ActionExecutionContext.ofPlatformAction(module, action, recordIds, CurrentUserContext.currentUser()).actionPolicy();
        if (!policy.requiresDataScope()) return DataScopeCriteriaResult.unrestricted(Criteria.of());
        Criteria criteria = recordIds.size() == 1 ? Criteria.of().eq("id", recordIds.iterator().next()) : Criteria.of().in("id", List.copyOf(recordIds));
        DataScopeCriteriaResult scope = dataScope.resolveReadScope(module, policy, criteria, CurrentUserContext.currentUser());
        long visible = withTenantScope(scope, () -> entityService(module, entity).list(scope.criteria(), new PageRequest(0, recordIds.size())).stream()
                .map(DynamicRecord::getId).filter(recordIds::contains).distinct().count());
        if (visible != recordIds.size()) throw new PlatformException("record data permission denied: " + module + "." + policy.actionCode());
        return scope;
    }

    private DataScopeCriteriaResult sortMutationScope(String module, String entity, Set<String> explicitIds,
                                                       Criteria sortScope,
                                                       Supplier<Set<String>> collector) {
        actionPolicy.requireRecordAction(ActionExecutionContext.ofPlatformAction(module, PlatformAction.SORT,
                explicitIds, CurrentUserContext.currentUser()));
        DataScopeCriteriaResult explicitScope = requireRecordScope(module, entity, PlatformAction.SORT, explicitIds);
        Set<String> allIds = withTenantScope(explicitScope, () -> {
            requirePageSortScope(module, entity, sortScope, explicitIds);
            return collector.get();
        });
        return requireRecordScope(module, entity, PlatformAction.SORT, allIds);
    }

    private void requirePageSortScope(String module, String entity, Criteria sortScope,
                                      Set<String> explicitIds) {
        // Reject out-of-scope placement records before collecting affected siblings.
        if (sortScope == null || sortScope.isEmpty() || explicitIds.isEmpty()) return;
        DynamicEntityService service = entityService(module, entity);
        Criteria idsCriteria = explicitIds.size() == 1 ? Criteria.of().eq("id", explicitIds.iterator().next())
                : Criteria.of().in("id", List.copyOf(explicitIds));
        long visible = service.list(and(sortScope, idsCriteria), new PageRequest(0, explicitIds.size())).stream()
                .map(DynamicRecord::getId).filter(explicitIds::contains).distinct().count();
        if (visible != explicitIds.size()) {
            throw new PlatformException("record is outside tree sort scope: " + module + "." + entity);
        }
    }

    private DataScopeCriteriaResult requireRecordScope(String module, String entity, PlatformAction action,
                                                        Set<String> recordIds) {
        if (!supportsCapability(module, entity, EntityCapability.DATA_SCOPE)) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        if (recordIds.isEmpty()) {
            throw new IllegalArgumentException("record action requires record ids: " + module + "." + action.code());
        }
        var policy = ActionExecutionContext.ofPlatformAction(module, action, recordIds,
                CurrentUserContext.currentUser()).actionPolicy();
        if (!policy.requiresDataScope()) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        Criteria criteria = recordIds.size() == 1
                ? Criteria.of().eq("id", recordIds.iterator().next())
                : Criteria.of().in("id", List.copyOf(recordIds));
        DataScopeCriteriaResult scope = dataScope.resolveReadScope(module, policy, criteria, CurrentUserContext.currentUser());
        long visible = withTenantScope(scope, () -> entityService(module, entity)
                .list(scope.criteria(), new PageRequest(0, recordIds.size())).stream()
                .map(DynamicRecord::getId)
                .filter(recordIds::contains)
                .distinct()
                .count());
        if (visible != recordIds.size()) {
            throw new PlatformException("record data permission denied: " + module + "." + policy.actionCode());
        }
        return scope;
    }

    private Set<String> sortScopeRecordIds(String module, String entity, String id, String targetId) {
        Set<String> result = new LinkedHashSet<>(ids(Arrays.asList(id, targetId)));
        DynamicEntityService service = entityService(module, entity);
        DynamicRecord moving = service.select(id);
        DynamicRecord target = targetId == null || targetId.isBlank() ? null : service.select(targetId);
        if (moving == null || target == null) {
            return result;
        }
        service.sortPartition().requireSamePartition(moving, target);
        service.sortedList(service.sortPartition().criteriaFor(moving)).stream().map(DynamicRecord::getId).forEach(result::add);
        return result;
    }

    private Set<String> treeSortScopeRecordIds(String module, String entity, String id, String previousId,
                                                String nextId, String parentId, Criteria sortScope) {
        Set<String> result = new LinkedHashSet<>(ids(Arrays.asList(id, previousId, nextId)));
        DynamicEntityService service = entityService(module, entity);
        DynamicRecord moving = service.select(id);
        if (moving == null) {
            return result;
        }
        String targetParent = normalizeParent(parentId);
        if (targetParent == null) targetParent = parentOf(service, previousId);
        if (targetParent == null) targetParent = parentOf(service, nextId);
        if (targetParent == null) targetParent = normalizeParent(moving.parentId());
        if (targetParent == null) targetParent = TreeAbility.ROOT_ID;
        if (!TreeAbility.ROOT_ID.equals(targetParent)) result.add(targetParent);
        service.children(sortScope, targetParent).stream()
                .map(DynamicRecord::getId).forEach(result::add);
        return result;
    }

    private Criteria and(Criteria left, Criteria right) {
        Criteria result = Criteria.of();
        if (left != null && !left.isEmpty()) result.andGroup(left.getRoot());
        if (right != null && !right.isEmpty()) result.andGroup(right.getRoot());
        return result;
    }

    private Set<String> treeExplicitIds(String id, String previousId, String nextId, String parentId) {
        LinkedHashSet<String> result = new LinkedHashSet<>(ids(Arrays.asList(id, previousId, nextId)));
        String parent = normalizeParent(parentId);
        if (parent != null && !TreeAbility.ROOT_ID.equals(parent)) result.add(parent);
        return java.util.Collections.unmodifiableSet(result);
    }

    private String parentOf(DynamicEntityService service, String neighborId) {
        if (neighborId == null || neighborId.isBlank()) return null;
        DynamicRecord neighbor = service.select(neighborId);
        return neighbor == null ? null : normalizeParent(neighbor.parentId());
    }

    private String normalizeParent(String parentId) {
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    private boolean requiresSortScope(RuntimeMutationSource source) {
        return source == RuntimeMutationSource.BUSINESS || source == RuntimeMutationSource.ACTION;
    }

    private boolean supportsCapability(String module, String entity, EntityCapability capability) {
        return runtime.describe(module).entities().stream().filter(item -> item.entityAlias().equals(entity)).findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown dynamic entity: " + module + "." + entity))
                .capabilities().contains(capability.name());
    }

    private DynamicEntityService entityService(String module, String entity) { return runtime.entityService(module, entity); }
    private <T> T withTenantScope(DataScopeCriteriaResult scope, Supplier<T> supplier) {
        if (scope.crossTenant()) try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("data scope allows cross-tenant read")) { return supplier.get(); }
        return supplier.get();
    }
    private Set<String> ids(String id) { return ids(id == null ? List.of() : List.of(id)); }
    private Set<String> ids(Collection<String> values) { if (values == null || values.isEmpty()) return Set.of(); LinkedHashSet<String> result = new LinkedHashSet<>(); values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).forEach(result::add); return java.util.Collections.unmodifiableSet(result); }
    private void ensureId(DynamicRecord record) { if (record != null && (record.getId() == null || record.getId().isBlank())) record.setId(Ids.newId()); }
    private DynamicRecordMutationEvent event(DynamicRecordMutationEventType type, String module, String entity, String id, DynamicRecordSaveOperation operation, DynamicRecord before, DynamicRecord after, DynamicMutationContext context) {
        return new DynamicRecordMutationEvent(null, type, module, entity, id, operation, before == null ? null : before.copy(), after == null ? null : after.copy(), context.mutationSource(), context.traceId(), context.depth(), context.parentExecutionId(), context.cascadeAllowed(), context.metadata());
    }
    private DynamicRecordEventPublisher.DynamicRecordEventContext eventContext(String module, String entity, RuntimeMutationSource source, String traceId) {
        DynamicMutationContext context = DynamicMutationContext.current().orElse(null); boolean writeBack = source == RuntimeMutationSource.WRITE_BACK && context != null;
        return new DynamicRecordEventPublisher.DynamicRecordEventContext(module, entity, writeBack ? context.traceId() : traceId, TenantContext.currentTenantId().orElse(null), TenantContext.isSystem(), TenantContext.systemReason().orElse(null), source, writeBack ? context.depth() : 0, writeBack ? context.parentExecutionId() : null, !writeBack || context.cascadeAllowed());
    }

    private enum Kind { CREATE, UPDATE, DELETE }
    private record ChildMutation(Kind kind, DynamicRelationDescriptor relation, DynamicRecord parentBefore, DynamicRecord before, DynamicRecord incoming) {
        static ChildMutation create(DynamicRelationDescriptor relation, DynamicRecord incoming) { return new ChildMutation(Kind.CREATE, relation, null, null, incoming); }
        static ChildMutation update(DynamicRelationDescriptor relation, DynamicRecord before, DynamicRecord incoming) { return new ChildMutation(Kind.UPDATE, relation, null, before, incoming); }
        static ChildMutation delete(DynamicRelationDescriptor relation, DynamicRecord before) { return new ChildMutation(Kind.DELETE, relation, null, before, null); }
        ChildMutation withParentBefore(DynamicRecord parent) { return new ChildMutation(kind, relation, parent, before, incoming); }
    }
}
