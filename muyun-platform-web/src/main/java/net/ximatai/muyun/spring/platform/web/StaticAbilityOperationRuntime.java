package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.platform.web.ProjectedRecordValues;
import net.ximatai.muyun.spring.platform.web.RecordReadVisibility;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.StaticWebOperationTarget;
import net.ximatai.muyun.spring.common.model.capability.SortCapable;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.title.RecordLabelResolver;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.platform.deletion.PurgeReport;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinActionOutcome;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinFacade;
import net.ximatai.muyun.spring.platform.deletion.RecycleBinItem;
import net.ximatai.muyun.spring.platform.deletion.RestoreReport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Executes standard static ability operations independently from Spring MVC handler methods. */
public final class StaticAbilityOperationRuntime {
    private final ObjectProvider<RecycleBinFacade> recycleBinFacade;
    private final StaticCapabilityActionRuntimeAdapter capabilityActionAdapter = new StaticCapabilityActionRuntimeAdapter();

    public StaticAbilityOperationRuntime(ObjectProvider<RecycleBinFacade> recycleBinFacade) {
        this.recycleBinFacade = recycleBinFacade;
    }

    public Object execute(RegisteredWebEndpoint endpoint, HttpServletRequest request, Object body) {
        StaticWebOperationTarget target = endpoint.staticTarget();
        if (target == null) {
            throw new IllegalStateException("static operation target is required by "
                    + endpoint.definition().endpointId());
        }
        OperationScope scope = new OperationScope(target);
        PlatformAction action = endpoint.definition().action();
        var capabilityAction = CapabilityModuleRegistry.defaultRegistry().actionOwner(action);
        if (capabilityAction.isPresent()) {
            return capabilityActionAdapter.execute(capabilityAction.get(), scope, request, action, pathVariable(request, "id"),
                    (RecordActionWebRequest) body);
        }
        return switch (action) {
            case SORT -> target.service() instanceof TreeAbility<?>
                    ? sortTree(scope, request, pathVariable(request, "id"), (TreeSortWebRequest) body)
                    : sort(scope, request, pathVariable(request, "id"), (SortWebRequest) body);
            case TREE -> tree(scope, request, endpoint.definition().operationCode(), (WebQueryRequest) body);
            case RECYCLE_BIN_QUERY -> "view".equals(endpoint.definition().operationCode())
                    ? recycleBinView(scope, pathVariable(request, "id"))
                    : recycleBin(scope, (WebQueryRequest) body);
            case RECYCLE_BIN_RESTORE -> restore(scope, pathVariable(request, "sourceDeleteOperationId"));
            case RECYCLE_BIN_PURGE -> purge(scope, pathVariable(request, "sourceDeleteOperationId"));
            default -> throw new IllegalStateException("unsupported compiled static operation: "
                    + endpoint.definition().action());
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int sort(OperationScope scope,
                     HttpServletRequest httpRequest,
                     String id,
                     SortWebRequest request) {
        SortAbility ability = requireService(scope, SortAbility.class);
        SortWebRequest normalized = request == null ? new SortWebRequest(null, null) : request;
        return MutationTenantScopeExecutor.forExistingRecord(scope, id, () -> scope.webScope(() -> {
            if (hasText(normalized.previousId())) {
                requireProjectionRecord(scope, httpRequest, PlatformAction.SORT, id);
                requireProjectionRecord(scope, httpRequest, PlatformAction.SORT, normalized.previousId());
                requireSortScope(ability, id, normalized.previousId());
                return StandardMutationResultSupport.sorted(scope, () -> {
                    ability.moveAfter(id, normalized.previousId());
                    return 1;
                });
            }
            if (hasText(normalized.nextId())) {
                requireProjectionRecord(scope, httpRequest, PlatformAction.SORT, id);
                requireProjectionRecord(scope, httpRequest, PlatformAction.SORT, normalized.nextId());
                requireSortScope(ability, id, normalized.nextId());
                return StandardMutationResultSupport.sorted(scope, () -> {
                    ability.moveBefore(id, normalized.nextId());
                    return 1;
                });
            }
            throw new IllegalArgumentException("sort requires previousId or nextId");
        }));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void requireSortScope(SortAbility ability, String id, String targetId) {
        if (!(ability instanceof DataScopeAbility<?> dataScopeAbility)) {
            return;
        }
        DataScopeAbility dataScope = DataScopeAbility.cast(dataScopeAbility);
        Set<String> explicitIds = normalizeIds(id, targetId);
        DataScopeCriteriaResult scope = dataScope.requireRecordScopeResult(
                PlatformAction.SORT.executionPolicy(), explicitIds);
        Set<String> scopedIds = (Set<String>) dataScope.withDataScopeTenant(scope, () -> {
            LinkedHashSet<String> ids = new LinkedHashSet<>(explicitIds);
            SortCapable moving = (SortCapable) ability.select(id);
            SortCapable target = (SortCapable) ability.select(targetId);
            if (moving != null && target != null) {
                ability.validateSortScope(moving, target);
                ((List<? extends SortCapable>) ability.sortedList(ability.sortScope(moving))).stream()
                        .map(SortCapable::getId)
                        .forEach(ids::add);
            }
            return Set.copyOf(ids);
        });
        dataScope.requireRecordScopeResult(PlatformAction.SORT.executionPolicy(), scopedIds);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int sortTree(OperationScope scope,
                         HttpServletRequest request,
                         String id,
                         TreeSortWebRequest body) {
        TreeAbility ability = requireService(scope, TreeAbility.class);
        TreeSortWebRequest sort = body == null ? new TreeSortWebRequest(null, null, null) : body;
        if (!hasText(sort.previousId()) && !hasText(sort.nextId()) && !hasText(sort.parentId())) {
            throw new IllegalArgumentException("tree sort requires previousId, nextId, or parentId");
        }
        return MutationTenantScopeExecutor.forExistingRecord(scope, id, () -> scope.webScope(() -> {
            TreeWebProjectionPolicy policy = treePolicy(scope);
            if (policy == null) {
                requireTreeSortScope(ability, id, sort);
            } else {
                policy.requireTreeSortScope(request, id, sort);
            }
            return StandardMutationResultSupport.sorted(scope, () -> {
                if (policy == null) {
                    ability.moveInTree(id, sort.previousId(), sort.nextId(), sort.parentId());
                } else {
                    policy.moveTree(request, id, sort);
                }
                return 1;
            });
        }));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void requireTreeSortScope(TreeAbility ability, String id, TreeSortWebRequest sort) {
        if (!(ability instanceof DataScopeAbility<?> dataScopeAbility)) {
            return;
        }
        DataScopeAbility dataScope = DataScopeAbility.cast(dataScopeAbility);
        Set<String> explicitIds = treeExplicitIds(id, sort.previousId(), sort.nextId(), sort.parentId());
        DataScopeCriteriaResult scope = dataScope.requireRecordScopeResult(
                PlatformAction.SORT.executionPolicy(), explicitIds);
        Set<String> scopedIds = (Set<String>) dataScope.withDataScopeTenant(scope, () -> {
            LinkedHashSet<String> ids = new LinkedHashSet<>(explicitIds);
            TreeCapable moving = (TreeCapable) ability.select(id);
            if (moving == null) {
                return Set.copyOf(ids);
            }
            String parentId = normalizeParent(sort.parentId());
            if (parentId == null) parentId = neighborParent(ability, sort.previousId());
            if (parentId == null) parentId = neighborParent(ability, sort.nextId());
            if (parentId == null) parentId = normalizeParent(moving.getParentId());
            if (parentId == null) parentId = TreeAbility.ROOT_ID;
            if (!TreeAbility.ROOT_ID.equals(parentId)) ids.add(parentId);
            ((List<? extends EntityContract>) ability.children(parentId)).stream()
                    .map(EntityContract::getId)
                    .forEach(ids::add);
            return Set.copyOf(ids);
        });
        dataScope.requireRecordScopeResult(PlatformAction.SORT.executionPolicy(), scopedIds);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object tree(OperationScope scope,
                        HttpServletRequest request,
                        String operationCode,
                        WebQueryRequest queryRequest) {
        TreeAbility ability = requireService(scope, TreeAbility.class);
        if ("treeQuery".equals(operationCode)) {
            TreeWebQuerySupport.bind(request, queryRequest);
        }
        boolean flat = Boolean.parseBoolean(request.getParameter("flat"));
        return scope.webScope(() -> {
            if ("tree".equals(operationCode) || "treeQuery".equals(operationCode)) {
                List<EntityContract> roots = treeChildren(scope, ability, request, TreeAbility.ROOT_ID);
                if (flat) {
                    List<EntityContract> rows = new ArrayList<>();
                    roots.forEach(root -> appendDescendants(scope, ability, request, root, rows));
                    return new WebListResponse<>(WebOutputSupport.records(
                            ability, rows, FieldOutputContext.VIEW));
                }
                return new WebListResponse<>(roots.stream()
                        .map(root -> treeNode(scope, ability, request, root)).toList());
            }
            String id = pathVariable(request, "id");
            boolean includeSelf = request.getParameter("includeSelf") == null
                    || Boolean.parseBoolean(request.getParameter("includeSelf"));
            EntityContract root = treeSelect(scope, ability, request, id);
            if (root == null) return new WebListResponse<>(List.of());
            if (!flat) {
                if (includeSelf) {
                    return new WebListResponse<>(List.of(treeNode(scope, ability, request, root)));
                }
                return new WebListResponse<>(treeChildren(scope, ability, request, root.getId()).stream()
                        .map(child -> treeNode(scope, ability, request, child)).toList());
            }
            List<EntityContract> rows = new ArrayList<>();
            if (includeSelf) rows.add(root);
            for (EntityContract child : treeChildren(scope, ability, request, root.getId())) {
                appendDescendants(scope, ability, request, child, rows);
            }
            return new WebListResponse<>(WebOutputSupport.records(ability, rows, FieldOutputContext.VIEW));
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private EntityContract treeSelect(OperationScope scope,
                                      TreeAbility ability,
                                      HttpServletRequest request,
                                      String id) {
        TreeWebProjectionPolicy policy = treePolicy(scope);
        if (policy != null) {
            return (EntityContract) policy.treeSelect(request, id);
        }
        if (ability instanceof DataScopeAbility<?> scoped) {
            return (EntityContract) DataScopeAbility.cast(scoped).selectForAction(PlatformAction.TREE, id);
        }
        return (EntityContract) ability.select(id);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<EntityContract> treeChildren(OperationScope scope,
                                              TreeAbility ability,
                                              HttpServletRequest request,
                                              String parentId) {
        TreeWebProjectionPolicy policy = treePolicy(scope);
        if (policy != null) {
            return (List<EntityContract>) (List<?>) policy.treeChildren(request, parentId);
        }
        if (ability instanceof DataScopeAbility<?> scoped) {
            return (List<EntityContract>) (List<?>) DataScopeAbility.cast(scoped)
                    .childrenForAction(PlatformAction.TREE, parentId);
        }
        return (List<EntityContract>) (List<?>) ability.children(parentId);
    }

    private void appendDescendants(OperationScope scope,
                                   TreeAbility<?> ability,
                                   HttpServletRequest request,
                                   EntityContract record,
                                   List<EntityContract> rows) {
        rows.add(record);
        for (EntityContract child : treeChildren(scope, ability, request, record.getId())) {
            appendDescendants(scope, ability, request, child, rows);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private WebTreeNode<EntityContract> treeNode(OperationScope scope,
                                                 TreeAbility<?> ability,
                                                 HttpServletRequest request,
                                                 EntityContract record) {
        return new WebTreeNode<>(WebOutputSupport.record((TreeAbility) ability, record, FieldOutputContext.VIEW),
                treeChildren(scope, ability, request, record.getId()).stream()
                        .map(child -> treeNode(scope, ability, request, child)).toList());
    }

    @SuppressWarnings("rawtypes")
    private TreeWebProjectionPolicy treePolicy(OperationScope scope) {
        return scope.target.anchor() instanceof TreeWebProjectionPolicy<?, ?> policy
                ? (TreeWebProjectionPolicy) policy
                : null;
    }

    private void requireProjectionRecord(OperationScope scope,
                                         HttpServletRequest request,
                                         PlatformAction action,
                                         String id) {
        if (scope.target.anchor() instanceof RecordWebProjectionPolicy policy) {
            policy.requireRecord(request, action, id);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object recycleBin(OperationScope scope, WebQueryRequest request) {
        RecycleBinAbility ability = requireService(scope, RecycleBinAbility.class);
        return scope.webScope(() -> {
            Optional<? extends WebPageResponse<?>> projected = recycleBinProjectedQuery(scope, request);
            if (projected.isPresent()) return decorateRecycleBin(ability, projected.get());
            WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
            Criteria criteria = recycleBinCriteria(scope, request);
            Sort[] sorts = recycleBinSorts(scope, request);
            PageRequest pageRequest = PageRequest.of(page.pageNum(), page.pageSize());
            WebPageResponse<?> response;
            if (ability instanceof DataScopeAbility<?> scoped) {
                DataScopeAbility dataScope = DataScopeAbility.cast(scoped);
                DataScopeCriteriaResult dataScopeResult = dataScope.readScopeByPolicy(
                        StaticStandardMutationSupport.actionPolicy(scope, PlatformAction.RECYCLE_BIN_QUERY), criteria);
                response = (WebPageResponse<?>) dataScope.withDataScopeTenant(dataScopeResult,
                        () -> WebPageResponse.from(ability.pageRecycleBin(
                                dataScopeResult.criteria(), pageRequest, sorts)));
            } else {
                response = WebPageResponse.from(ability.pageRecycleBin(criteria, pageRequest, sorts));
            }
            return decorateRecycleBin(ability, projectStaticFallback(scope, response));
        });
    }

    /**
     * Reads one retained record through the same retained visibility and data-scope policy as the recycle-bin list.
     * This deliberately does not reuse the normal CRUD view endpoint: a soft-deleted record is not visible there.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private EntityContract recycleBinView(OperationScope scope, String id) {
        RecycleBinAbility ability = requireService(scope, RecycleBinAbility.class);
        return (EntityContract) scope.webScope(() -> {
            Criteria criteria = Criteria.of().eq("id", id);
            List<?> records;
            if (ability instanceof DataScopeAbility<?> scoped) {
                DataScopeAbility dataScope = DataScopeAbility.cast(scoped);
                DataScopeCriteriaResult dataScopeResult = dataScope.readScopeByPolicy(
                        StaticStandardMutationSupport.actionPolicy(scope, PlatformAction.RECYCLE_BIN_QUERY), criteria);
                records = (List<?>) dataScope.withDataScopeTenant(dataScopeResult,
                        () -> ability.pageRecycleBin(dataScopeResult.criteria(), PageRequest.of(1, 1)).getRecords());
            } else {
                records = ability.pageRecycleBin(criteria, PageRequest.of(1, 1)).getRecords();
            }
            if (records.isEmpty()) {
                throw new IllegalArgumentException("recycle-bin record not found: " + id);
            }
            return WebOutputSupport.record(ability, (EntityContract) records.getFirst(), FieldOutputContext.VIEW);
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Optional<? extends WebPageResponse<?>> recycleBinProjectedQuery(OperationScope scope,
                                                                             WebQueryRequest request) {
        if (!(scope.target.anchor() instanceof CrudWeb<?, ?> crudWeb)) return Optional.empty();
        return ((CrudWeb) crudWeb).queryStaticProjectedList(request, RecordReadVisibility.RETAINED);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private WebPageResponse<?> projectStaticFallback(OperationScope scope, WebPageResponse<?> response) {
        if (!(scope.target.anchor() instanceof CrudWeb<?, ?> crudWeb)) return response;
        return ((CrudWeb) crudWeb).projectStaticDefaultList(response);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Criteria recycleBinCriteria(OperationScope scope, WebQueryRequest request) {
        if (scope.target.anchor() instanceof CrudWeb<?, ?> crudWeb) {
            return ((CrudWeb) crudWeb).queryCriteria(request);
        }
        return Criteria.of();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Sort[] recycleBinSorts(OperationScope scope, WebQueryRequest request) {
        if (scope.target.anchor() instanceof CrudWeb<?, ?> crudWeb) {
            return ((CrudWeb) crudWeb).querySorts(request);
        }
        return new Sort[0];
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private WebPageResponse<RecycleBinItem<?>> decorateRecycleBin(RecycleBinAbility ability,
                                                                   WebPageResponse<?> response) {
        List<? extends RecycleBinItem<?>> records = facade().items(ability, response.records(),
                ProjectedRecordValues::id, ProjectedRecordValues::deletedAt);
        return new WebPageResponse<>(List.copyOf(records), response.total(), response.pageNum(), response.pageSize(),
                response.pages(), response.totalKnown(), response.navigation());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RestoreReport restore(OperationScope scope, String sourceDeleteOperationId) {
        RecycleBinAbility ability = requireService(scope, RecycleBinAbility.class);
        return scope.webScope(() -> {
            RecycleBinActionOutcome<EntityContract, RestoreReport> outcome =
                    (RecycleBinActionOutcome<EntityContract, RestoreReport>) (RecycleBinActionOutcome)
                            facade().restoreWithSource(ability, sourceDeleteOperationId);
            RecycleBinMutationResultSupport.restored(scope.webScopeName(), outcome.recordId(),
                    recordLabel(outcome.record(), ability), outcome.report());
            return outcome.report();
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PurgeReport purge(OperationScope scope, String sourceDeleteOperationId) {
        RecycleBinAbility ability = requireService(scope, RecycleBinAbility.class);
        return scope.webScope(() -> {
            RecycleBinActionOutcome<EntityContract, PurgeReport> outcome =
                    (RecycleBinActionOutcome<EntityContract, PurgeReport>) (RecycleBinActionOutcome)
                            facade().purgeWithSource(ability, sourceDeleteOperationId);
            RecycleBinMutationResultSupport.purged(scope.webScopeName(), outcome.recordId(),
                    recordLabel(outcome.record(), ability), outcome.report());
            return outcome.report();
        });
    }

    private RecycleBinFacade facade() {
        RecycleBinFacade facade = recycleBinFacade.getIfAvailable();
        if (facade == null) throw new IllegalStateException("RecycleBinFacade is required by recycle-bin operation");
        return facade;
    }

    private String recordLabel(EntityContract record, Object service) {
        String label = RecordLabelResolver.readAsString(record);
        if (label == null || !(service instanceof FieldProtectionAbility<?> protection)) return label;
        String field = RecordLabelResolver.resolveFieldName(record.getClass()).orElse(null);
        Object masked = protection.maskProtectedValue(field, label, FieldOutputContext.VIEW);
        return masked == null ? null : String.valueOf(masked);
    }

    private String pathVariable(HttpServletRequest request, String name) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> values) || values.get(name) == null) {
            throw new IllegalArgumentException("missing path variable: " + name);
        }
        return String.valueOf(values.get(name));
    }

    @SuppressWarnings("unchecked")
    private <T> T requireService(OperationScope scope, Class<T> type) {
        if (!type.isInstance(scope.service())) {
            throw new IllegalStateException(scope.webScopeName() + " requires " + type.getSimpleName());
        }
        return (T) scope.service();
    }

    private Set<String> treeExplicitIds(String... ids) {
        LinkedHashSet<String> result = new LinkedHashSet<>(normalizeIds(ids[0], ids[1], ids[2]));
        String parent = normalizeParent(ids[3]);
        if (parent != null && !TreeAbility.ROOT_ID.equals(parent)) result.add(parent);
        return Set.copyOf(result);
    }

    private String neighborParent(TreeAbility<?> ability, String id) {
        if (!hasText(id)) return null;
        Object record = ability.select(id);
        return record instanceof TreeCapable tree ? normalizeParent(tree.getParentId()) : null;
    }

    private Set<String> normalizeIds(String... ids) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(ids).filter(this::hasText).map(String::trim).forEach(result::add);
        return Set.copyOf(result);
    }

    private String normalizeParent(String parentId) {
        return hasText(parentId) ? parentId.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Static source adapter registry; each capability owns its own service contract here. */
    private final class StaticCapabilityActionRuntimeAdapter {
        private final List<Handler> handlers = List.of(new EnableHandler());

        int execute(net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution contribution,
                    OperationScope scope,
                    HttpServletRequest request,
                    PlatformAction action,
                    String id,
                    RecordActionWebRequest body) {
            return handlers.stream().filter(handler -> handler.supports(contribution)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no static runtime adapter for capability action: "
                            + action.code()))
                    .execute(scope, request, action, id, body);
        }

        private interface Handler {
            boolean supports(net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution contribution);

            int execute(OperationScope scope, HttpServletRequest request, PlatformAction action, String id,
                        RecordActionWebRequest body);
        }

        private final class EnableHandler implements Handler {
            @Override
            public boolean supports(net.ximatai.muyun.spring.dynamic.capability.CapabilityActionContribution contribution) {
                return contribution instanceof net.ximatai.muyun.spring.dynamic.capability.EnableCapabilityActionFacet;
            }

            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public int execute(OperationScope scope, HttpServletRequest httpRequest, PlatformAction action, String id,
                               RecordActionWebRequest request) {
                EnableAbility ability = requireService(scope, EnableAbility.class);
                RecordActionWebRequest normalized = request == null ? new RecordActionWebRequest(null) : request;
                return MutationTenantScopeExecutor.forExistingRecord(scope, id, () -> scope.webScope(() -> {
                    requireProjectionRecord(scope, httpRequest, action, id);
                    StaticStandardMutationSupport.requireDataScopeRecord((ScopedWeb) scope, action, id);
                    EntityContract existing = (EntityContract) ability.select(id);
                    return switch (action) {
                        case ENABLE -> StandardMutationResultSupport.enabled(scope, id, recordLabel(existing, ability),
                                () -> ability.enable(id, normalized.version()));
                        case DISABLE -> StandardMutationResultSupport.disabled(scope, id, recordLabel(existing, ability),
                                () -> ability.disable(id, normalized.version()));
                        default -> throw new IllegalArgumentException("ENABLE static adapter does not own: " + action.code());
                    };
                }));
            }
        }
    }

    private static final class OperationScope implements ScopedWeb<Object>, MutationTenantScopeResolver<EntityContract> {
        private final StaticWebOperationTarget target;

        private OperationScope(StaticWebOperationTarget target) {
            this.target = target;
        }

        @Override
        public Object service() {
            return target.service();
        }

        @Override
        public <T> T webScope(java.util.function.Supplier<T> action) {
            return target.anchor().webScope(action);
        }

        @Override
        public String webScopeName() {
            return target.moduleAlias();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<String> tenantIdForCreate(EntityContract record) {
            return target.anchor() instanceof MutationTenantScopeResolver<?> resolver
                    ? ((MutationTenantScopeResolver<EntityContract>) resolver).tenantIdForCreate(record)
                    : Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<String> tenantIdForUpdate(String id, EntityContract record) {
            return target.anchor() instanceof MutationTenantScopeResolver<?> resolver
                    ? ((MutationTenantScopeResolver<EntityContract>) resolver).tenantIdForUpdate(id, record)
                    : Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<String> tenantIdForExistingRecord(String id) {
            return target.anchor() instanceof MutationTenantScopeResolver<?> resolver
                    ? ((MutationTenantScopeResolver<EntityContract>) resolver).tenantIdForExistingRecord(id)
                    : Optional.empty();
        }
    }
}
