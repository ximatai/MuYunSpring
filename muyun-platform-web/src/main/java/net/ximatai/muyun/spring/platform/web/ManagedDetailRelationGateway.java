package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.web.RecordActionWebRequest;
import net.ximatai.muyun.spring.web.WebOutputSupport;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime gateway for a compiled, one-level managed detail relation. */
@Component
public class ManagedDetailRelationGateway implements SmartInitializingSingleton {
    private final ModuleExecutionPlanCatalog planCatalog;
    private final ActionExecutionPolicyService actionPolicyService;
    private final ActionEndpointContextResolver actionContextResolver;
    private final ObjectMapper objectMapper;
    private final Map<RelationKey, StaticManagedDetailRelationHandler<?, ?>> handlers;

    @Autowired
    public ManagedDetailRelationGateway(ModuleExecutionPlanCatalog planCatalog,
                                        ActionExecutionPolicyService actionPolicyService,
                                        ActionEndpointContextResolver actionContextResolver,
                                        ObjectMapper objectMapper,
                                        List<StaticManagedDetailRelationHandler<?, ?>> handlers) {
        this.planCatalog = planCatalog;
        this.actionPolicyService = actionPolicyService;
        this.actionContextResolver = actionContextResolver;
        this.objectMapper = objectMapper;
        LinkedHashMap<RelationKey, StaticManagedDetailRelationHandler<?, ?>> indexed = new LinkedHashMap<>();
        for (StaticManagedDetailRelationHandler<?, ?> handler : handlers) {
            RelationKey key = new RelationKey(handler.parentModuleAlias(), handler.relationCode());
            if (indexed.putIfAbsent(key, handler) != null) {
                throw new IllegalStateException("duplicate managed detail relation handler: " + key);
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    ManagedDetailRelationGateway(ModuleExecutionPlanCatalog planCatalog,
                                 ActionExecutionPolicyService actionPolicyService,
                                 ObjectMapper objectMapper,
                                 List<StaticManagedDetailRelationHandler<?, ?>> handlers) {
        this(planCatalog, actionPolicyService, new ActionEndpointContextResolver(), objectMapper, handlers);
    }

    /** Binds every static managed declaration to exactly one domain adapter before serving requests. */
    @Override
    public void afterSingletonsInstantiated() {
        Map<RelationKey, net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor> declared =
                new LinkedHashMap<>();
        planCatalog.plans().forEach((moduleAlias, plan) -> plan.uiDescriptor().detailRelations().stream()
                .filter(relation -> relation.queryContract() != null && relation.queryContract().managedGateway())
                .forEach(relation -> declared.put(new RelationKey(moduleAlias, relation.code()), relation)));
        declared.forEach((key, relation) -> validateBinding(key, relation, handlers.get(key)));
        handlers.forEach((key, handler) -> {
            if (!declared.containsKey(key)) {
                throw new IllegalStateException("managed detail relation handler has no compiled declaration: " + key);
            }
        });
    }

    private static void validateBinding(RelationKey key,
                                        net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor relation,
                                        StaticManagedDetailRelationHandler<?, ?> handler) {
        if (handler == null) throw new IllegalStateException("no managed detail relation handler: " + key);
        if (!relation.sourceModuleAlias().equals(handler.parentModuleAlias())
                || !relation.targetEntityAlias().equals(handler.childEntityAlias())
                || !relation.parentBinding().equals(handler.parentBinding())
                || !java.util.Objects.equals(relation.parentConstraint(), handler.parentConstraint())) {
            throw new IllegalStateException("managed detail relation handler does not match compiled declaration: " + key);
        }
    }

    public WebPageResponse<?> query(String moduleAlias, CrudAbility<?> parentService, String parentId,
                                    String relationCode, WebQueryRequest request) {
        RelationRuntime runtime = requireRuntime(moduleAlias, parentService, parentId, relationCode, PlatformAction.VIEW);
        if (!runtime.relation().hasExecutableQueryContract()
                || !runtime.relation().queryContract().managedGateway()) {
            throw new IllegalStateException("managed detail relation query is not declared: " + relationCode);
        }
        requireRelationAction(runtime, runtime.relation().queryContract().actionCode(), null);
        Criteria criteria = runtime.handler().criteriaFor(runtime.parent());
        QueryAbility<?> queryAbility = runtime.handler().childService() instanceof QueryAbility<?> ability ? ability : null;
        if (queryAbility != null) {
            Criteria requested = queryAbility.queryCriteria(WebQueryRequests.from(request));
            if (requested != null) criteria.and(requested);
        } else if (request != null && (!request.conditions().isEmpty() || request.criteria() != null && !request.criteria().isEmpty())) {
            throw new IllegalArgumentException("managed relation query conditions are not supported: " + relationCode);
        }
        Sort[] sorts = queryAbility == null ? new Sort[0] : queryAbility.querySorts(WebQueryRequests.from(request));
        WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
        PageResult<?> result;
        if (runtime.handler().childService() instanceof DataScopeAbility<?> scoped) {
            result = scoped.pageQueryForAction(PlatformAction.QUERY, criteria,
                    PageRequest.of(page.pageNum(), page.pageSize()), sorts == null ? new Sort[0] : sorts);
        } else {
            result = runtime.handler().childService().pageQuery(criteria,
                    PageRequest.of(page.pageNum(), page.pageSize()), sorts == null ? new Sort[0] : sorts);
        }
        return WebPageResponse.from(WebOutputSupport.page((CrudAbility) runtime.handler().childService(),
                (PageResult) result, FieldOutputContext.LIST));
    }

    public Object insert(String moduleAlias, CrudAbility<?> parentService, String parentId,
                         String relationCode, Map<String, Object> payload) {
        RelationRuntime runtime = requireMutableRuntime(moduleAlias, parentService, parentId, relationCode,
                PlatformAction.CREATE, "createAllowed");
        EntityContract child = convertForCreate(runtime, payload);
        runtime.handler().bindParent(child, runtime.parent());
        requireRelationAction(runtime, runtime.relation().mutationContract().createActionCode(), null);
        String id = runtime.handler().childService().insert(child);
        return outputRecord(runtime, id);
    }

    public Object update(String moduleAlias, CrudAbility<?> parentService, String parentId, String relationCode,
                         String childId, Map<String, Object> payload) {
        RelationRuntime runtime = requireMutableRuntime(moduleAlias, parentService, parentId, relationCode,
                PlatformAction.UPDATE, "updateAllowed");
        EntityContract persisted = requireChild(runtime, childId, PlatformAction.UPDATE);
        if (payload == null || payload.get("version") == null) {
            throw new IllegalArgumentException("managed relation update requires record version: " + childId);
        }
        EntityContract child = mergeForUpdate(runtime, persisted, payload);
        child.setId(persisted.getId());
        runtime.handler().bindParent(child, runtime.parent());
        runtime.handler().childService().update(child);
        return outputRecord(runtime, childId);
    }

    public int delete(String moduleAlias, CrudAbility<?> parentService, String parentId, String relationCode,
                      String childId, RecordActionWebRequest request) {
        RelationRuntime runtime = requireMutableRuntime(moduleAlias, parentService, parentId, relationCode,
                PlatformAction.DELETE, "deleteAllowed");
        requireChild(runtime, childId, PlatformAction.DELETE);
        return runtime.handler().childService().delete(childId, request == null ? null : request.version());
    }

    private RelationRuntime requireRuntime(String moduleAlias, CrudAbility<?> parentService, String parentId,
                                           String relationCode, PlatformAction parentAction) {
        ModuleExecutionPlan plan = planCatalog.find(moduleAlias)
                .orElseThrow(() -> new IllegalStateException("managed detail relation requires compiled plan: " + moduleAlias));
        var relation = plan.uiDescriptor().detailRelations().stream()
                .filter(candidate -> candidate.code().equals(relationCode))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown managed detail relation: " + relationCode));
        StaticManagedDetailRelationHandler<?, ?> raw = handlers.get(new RelationKey(moduleAlias, relationCode));
        if (raw == null) throw new IllegalStateException("no managed detail relation handler: " + moduleAlias + "." + relationCode);
        validateBinding(new RelationKey(moduleAlias, relationCode), relation, raw);
        EntityContract parent = selectParent(parentService, parentAction, parentId);
        if (!availableFor(raw, parent)) {
            throw new IllegalStateException("managed detail relation is not applicable to parent: " + relationCode);
        }
        Set<String> editableFields = plan.uiDescriptor().editorContributions().stream()
                .filter(candidate -> candidate.resource().equals(relation.targetEntityAlias()))
                .findFirst()
                .map(contribution -> contribution.editor().fields().stream()
                        .map(field -> field.fieldRef().fieldName()).collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .orElseThrow(() -> new IllegalStateException("managed detail relation editor is missing: " + relationCode));
        return RelationRuntime.of(relation, raw, parent, editableFields);
    }

    private RelationRuntime requireMutableRuntime(String moduleAlias, CrudAbility<?> parentService, String parentId,
                                                  String relationCode, PlatformAction action, String allowedFlag) {
        RelationRuntime runtime = requireRuntime(moduleAlias, parentService, parentId, relationCode, PlatformAction.UPDATE);
        if (runtime.relation().readOnly() || runtime.relation().mutationContract() == null
                || !allows(runtime.relation().mutationContract(), allowedFlag)) {
            throw new IllegalStateException("managed detail relation mutation is not declared: " + relationCode + "." + action.code());
        }
        return runtime;
    }

    private static boolean allows(net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationMutationContract contract,
                                  String flag) {
        return switch (flag) {
            case "createAllowed" -> contract.createAllowed();
            case "updateAllowed" -> contract.updateAllowed();
            case "deleteAllowed" -> contract.deleteAllowed();
            default -> false;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EntityContract selectParent(CrudAbility<?> service, PlatformAction action, String parentId) {
        EntityContract parent;
        if (service instanceof DataScopeAbility<?> scoped) {
            parent = (EntityContract) ((DataScopeAbility) scoped).selectForAction(action, parentId);
        } else {
            parent = (EntityContract) service.select(parentId);
        }
        if (parent == null) throw new IllegalArgumentException("managed relation parent is not visible: " + parentId);
        return parent;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private EntityContract requireChild(RelationRuntime runtime, String childId, PlatformAction action) {
        String actionCode = switch (action) {
            case UPDATE -> runtime.relation().mutationContract().updateActionCode();
            case DELETE -> runtime.relation().mutationContract().deleteActionCode();
            default -> throw new IllegalArgumentException("unsupported managed child action: " + action);
        };
        requireRelationAction(runtime, actionCode, childId);
        CrudAbility service = runtime.handler().childService();
        EntityContract child = service instanceof DataScopeAbility<?> scoped
                ? (EntityContract) ((DataScopeAbility) scoped).selectForAction(action, childId)
                : (EntityContract) service.select(childId);
        if (child == null || !runtime.handler().belongsTo(child, runtime.parent())) {
            throw new IllegalArgumentException("managed relation child does not belong to parent: " + childId);
        }
        return child;
    }

    private void requireRelationAction(RelationRuntime runtime, String actionCode, String childId) {
        if (actionCode == null || actionCode.isBlank()) {
            throw new IllegalStateException("managed detail relation action is not compiled");
        }
        actionPolicyService.requireAuthorized(actionContextResolver.resolveActionCode(
                runtime.relation().sourceModuleAlias(), actionCode,
                childId == null ? Set.of() : Set.of(childId)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private EntityContract convertForCreate(RelationRuntime runtime, Map<String, Object> payload) {
        return (EntityContract) objectMapper.convertValue(acceptedPayload(runtime, payload, false),
                runtime.handler().childModelClass());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private EntityContract mergeForUpdate(RelationRuntime runtime, EntityContract persisted,
                                          Map<String, Object> payload) {
        EntityContract detached = (EntityContract) objectMapper.convertValue(persisted,
                runtime.handler().childModelClass());
        try {
            return (EntityContract) objectMapper.updateValue(detached, acceptedPayload(runtime, payload, true));
        } catch (com.fasterxml.jackson.databind.JsonMappingException exception) {
            throw new IllegalArgumentException("invalid managed relation update payload", exception);
        }
    }

    private static Map<String, Object> acceptedPayload(RelationRuntime runtime, Map<String, Object> payload,
                                                       boolean includeVersion) {
        if (payload == null) throw new IllegalArgumentException("managed relation payload must not be null");
        LinkedHashMap<String, Object> accepted = new LinkedHashMap<>();
        runtime.editableFields().forEach(field -> {
            if (payload.containsKey(field)) accepted.put(field, payload.get(field));
        });
        if (includeVersion && payload.containsKey("version")) accepted.put("version", payload.get("version"));
        return accepted;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EntityContract outputRecord(RelationRuntime runtime, String id) {
        CrudAbility service = runtime.handler().childService();
        return WebOutputSupport.record(service, (EntityContract) service.select(id), FieldOutputContext.VIEW);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean availableFor(StaticManagedDetailRelationHandler handler, EntityContract parent) {
        return handler.availableFor(parent);
    }

    private record RelationKey(String moduleAlias, String relationCode) { }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private record RelationRuntime(net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor relation,
                                   StaticManagedDetailRelationHandler handler,
                                   EntityContract parent,
                                   Set<String> editableFields) {
        static RelationRuntime of(net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor relation,
                                  StaticManagedDetailRelationHandler<?, ?> handler, EntityContract parent,
                                  Set<String> editableFields) {
            return new RelationRuntime(relation, handler, parent, Set.copyOf(editableFields));
        }
    }
}
