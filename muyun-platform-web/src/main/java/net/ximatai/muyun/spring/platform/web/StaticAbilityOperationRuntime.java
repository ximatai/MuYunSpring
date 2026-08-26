package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.CrudAbility;
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
import net.ximatai.muyun.spring.dynamic.capability.StaticCapabilityActionExecution;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.platform.deletion.PurgeReport;
import net.ximatai.muyun.spring.platform.deletion.DeletionLogService;
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
    private final ObjectProvider<DeletionLogService> deletionLogService;

    public StaticAbilityOperationRuntime(ObjectProvider<RecycleBinFacade> recycleBinFacade) {
        this(recycleBinFacade, null);
    }

    public StaticAbilityOperationRuntime(ObjectProvider<RecycleBinFacade> recycleBinFacade,
                                         ObjectProvider<DeletionLogService> deletionLogService) {
        this.recycleBinFacade = recycleBinFacade;
        this.deletionLogService = deletionLogService;
    }

    public Object execute(RegisteredWebEndpoint endpoint, HttpServletRequest request, Object body) {
        StaticWebOperationTarget target = endpoint.staticTarget();
        if (target == null) {
            throw new IllegalStateException("static operation target is required by "
                    + endpoint.definition().endpointId());
        }
        OperationScope scope = new OperationScope(target);
        PlatformAction action = endpoint.definition().action();
        var capabilityAction = CapabilityModuleRegistry.defaultRegistry().staticActionOwner(action, scope.service());
        if (capabilityAction.isPresent()) {
            return capabilityAction.get().staticRuntimeHandler()
                    .orElseThrow(() -> new IllegalStateException("no static runtime handler for capability action: "
                            + action.code()))
                    .execute(new StaticActionExecution(scope, request, endpoint.definition().operationCode(),
                            recordIdForAction(request, action, endpoint.definition().operationCode()), body), action);
        }
        throw new IllegalStateException("unsupported compiled static operation: " + endpoint.definition().action());
    }

    private String recordIdForAction(HttpServletRequest request, PlatformAction action, String operationCode) {
        return switch (action) {
            case ENABLE, DISABLE, SORT -> pathVariable(request, "id");
            case RECYCLE_BIN_QUERY -> "view".equals(operationCode) ? pathVariable(request, "id") : null;
            default -> null;
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
            requireProjectionRecord(scope, request, PlatformAction.SORT, id);
            if (hasText(sort.previousId())) {
                requireProjectionRecord(scope, request, PlatformAction.SORT, sort.previousId());
            }
            if (hasText(sort.nextId())) {
                requireProjectionRecord(scope, request, PlatformAction.SORT, sort.nextId());
            }
            if (hasText(sort.parentId()) && !TreeAbility.ROOT_ID.equals(sort.parentId())) {
                requireProjectionRecord(scope, request, PlatformAction.SORT, sort.parentId());
            }
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
        // The compiled plan owns endpoint aliases. A POST tree-query is identified by its typed
        // query payload, rather than re-interpreting a legacy operation-code spelling here.
        if (queryRequest != null) {
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
        requireCrudPageContextRecord(scope, id);
    }

    /**
     * A required navigator scope is a record-operation boundary, including operations delivered
     * by the generated ability dispatcher rather than {@link CrudWeb}'s own MVC defaults.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void requireCrudPageContextRecord(OperationScope scope, String id) {
        if (!(scope.target.anchor() instanceof CrudWeb crudWeb)) {
            return;
        }
        List<PageContextBindingDefinition> requiredScopeBindings = requiredCrudPageContextBindings(crudWeb);
        if (requiredScopeBindings.isEmpty()) {
            return;
        }
        if (!(scope.service() instanceof CrudAbility ability)) {
            throw new IllegalStateException(scope.webScopeName() + " requires CrudAbility for page record scope");
        }
        EntityContract record = (EntityContract) ability.select(id);
        PageContextScopePolicy.requireRecordInScope(record, requiredScopeBindings);
    }

    @SuppressWarnings("rawtypes")
    private void requireCrudPageContextRecord(OperationScope scope, EntityContract record) {
        if (!(scope.target.anchor() instanceof CrudWeb crudWeb)) {
            return;
        }
        List<PageContextBindingDefinition> requiredScopeBindings = requiredCrudPageContextBindings(crudWeb);
        if (!requiredScopeBindings.isEmpty()) {
            PageContextScopePolicy.requireRecordInScope(record, requiredScopeBindings);
        }
    }

    @SuppressWarnings("rawtypes")
    private List<PageContextBindingDefinition> requiredCrudPageContextBindings(CrudWeb crudWeb) {
        StandardModuleWebRuntime runtime = crudWeb.standardModuleWebRuntime();
        if (!crudWeb.requiresModuleExecutionPlan()
                && (runtime == null || !runtime.hasPlan(crudWeb.webScopeName()))) {
            return List.of();
        }
        return PageContextScopePolicy.recordScopeBindings(crudWeb.recordScopeBindings());
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
            EntityContract record = (EntityContract) records.getFirst();
            requireCrudPageContextRecord(scope, record);
            return WebOutputSupport.record(ability, record, FieldOutputContext.VIEW);
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
            requireRecycleBinSourceInPageScope(scope, ability, sourceDeleteOperationId);
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
            requireRecycleBinSourceInPageScope(scope, ability, sourceDeleteOperationId);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void requireRecycleBinSourceInPageScope(OperationScope scope,
                                                     RecycleBinAbility ability,
                                                     String sourceDeleteOperationId) {
        if (!(scope.target.anchor() instanceof CrudWeb crudWeb)
                || requiredCrudPageContextBindings(crudWeb).isEmpty()) {
            return;
        }
        DeletionLogService log = deletionLogService == null ? null : deletionLogService.getIfAvailable();
        if (log == null) {
            throw new IllegalStateException("DeletionLogService is required by scoped recycle-bin operation");
        }
        EntityContract record = (EntityContract) ability.selectIgnoreSoftDelete(
                log.operation(sourceDeleteOperationId).getRootRecordId());
        if (record != null) {
            requireCrudPageContextRecord(scope, record);
        }
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

    private final class StaticActionExecution implements StaticCapabilityActionExecution {
        private final OperationScope scope;
        private final HttpServletRequest request;
        private final String operationCode;
        private final String id;
        private final Object body;

        private StaticActionExecution(OperationScope scope, HttpServletRequest request, String operationCode, String id, Object body) {
            this.scope = scope;
            this.request = request;
            this.operationCode = operationCode;
            this.id = id;
            this.body = body;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Object executeEnable(PlatformAction action) {
            EnableAbility ability = requireService(scope, EnableAbility.class);
            RecordActionWebRequest normalized = body instanceof RecordActionWebRequest actionRequest
                    ? actionRequest : new RecordActionWebRequest(null);
            return MutationTenantScopeExecutor.forExistingRecord(scope, id, () -> scope.webScope(() -> {
                requireProjectionRecord(scope, request, action, id);
                StaticStandardMutationSupport.requireDataScopeRecord((ScopedWeb) scope, action, id);
                EntityContract existing = (EntityContract) ability.select(id);
                return switch (action) {
                    case ENABLE -> StandardMutationResultSupport.enabled(scope, id, recordLabel(existing, ability),
                            () -> ability.enable(id, normalized.version()));
                    case DISABLE -> StandardMutationResultSupport.disabled(scope, id, recordLabel(existing, ability),
                            () -> ability.disable(id, normalized.version()));
                    default -> throw new IllegalArgumentException("ENABLE static runtime handler does not own: " + action.code());
                };
            }));
        }

        @Override
        public Object executeSort() {
            return sort(scope, request, id, body instanceof SortWebRequest sort
                    ? sort : new SortWebRequest(null, null));
        }

        @Override
        public Object executeTree(PlatformAction action) {
            if (action == PlatformAction.TREE) {
                return tree(scope, request, operationCode, body instanceof WebQueryRequest query ? query : null);
            }
            if (action == PlatformAction.SORT) {
                return sortTree(scope, request, id, body instanceof TreeSortWebRequest tree
                        ? tree : new TreeSortWebRequest(null, null, null));
            }
            throw new IllegalArgumentException("TREE static runtime handler does not own: " + action.code());
        }

        @Override
        public Object executeRecycleBin(PlatformAction action) {
            return switch (action) {
                case RECYCLE_BIN_QUERY -> "view".equals(operationCode)
                        ? recycleBinView(scope, id) : recycleBin(scope, body instanceof WebQueryRequest query ? query : null);
                case RECYCLE_BIN_RESTORE -> restore(scope, pathVariable(request, "sourceDeleteOperationId"));
                case RECYCLE_BIN_PURGE -> purge(scope, pathVariable(request, "sourceDeleteOperationId"));
                default -> throw new IllegalArgumentException("RECYCLE_BIN static runtime handler does not own: " + action.code());
            };
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
