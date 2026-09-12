package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.AggregateQuery;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.form.FormSchema;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryCompiler;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebListQuerySummaryItem;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Source-neutral server-side runtime for standard module Web endpoints.
 *
 * <p>Controllers hand this facade only their stable module alias and service. It consumes the
 * catalogued execution plan; it never reaches back into a controller's declaration DSL.</p>
 */
@Component
public class StandardModuleWebRuntime {
    private final ModuleExecutionPlanCatalog executionPlans;
    private final StaticRecordReadProjectionService readProjectionService;
    private final ListQuerySummaryRuntime listQuerySummaryRuntime;

    public StandardModuleWebRuntime(ModuleExecutionPlanCatalog executionPlans,
                                    StaticRecordReadProjectionService readProjectionService) {
        this(executionPlans, readProjectionService, new ListQuerySummaryRuntime(java.util.List.of()));
    }

    @Autowired
    public StandardModuleWebRuntime(ModuleExecutionPlanCatalog executionPlans,
                                    StaticRecordReadProjectionService readProjectionService,
                                    ListQuerySummaryRuntime listQuerySummaryRuntime) {
        if (executionPlans == null || readProjectionService == null) {
            throw new IllegalArgumentException("standard module web runtime requires execution plan and read projection services");
        }
        this.executionPlans = executionPlans;
        this.readProjectionService = readProjectionService;
        this.listQuerySummaryRuntime = listQuerySummaryRuntime == null
                ? new ListQuerySummaryRuntime(java.util.List.of()) : listQuerySummaryRuntime;
    }

    public boolean hasPlan(String moduleAlias) {
        return executionPlans.find(moduleAlias).isPresent();
    }

    /** Returns the compiled plan or fails before a migrated endpoint can use a compatibility path. */
    public ModuleExecutionPlan requirePlan(String moduleAlias) {
        return executionPlans.find(moduleAlias).orElseThrow(() -> PlatformErrors.config(
                PlatformErrorCodes.CONFIG_MISSING,
                "no executable module plan is registered for module: " + moduleAlias,
                ErrorScope.module(moduleAlias)));
    }

    public Optional<QuerySchema> querySchema(String moduleAlias, CrudAbility<?> service) {
        return plan(moduleAlias).map(ModuleExecutionPlan::querySchema);
    }

    public Optional<Criteria> queryCriteria(String moduleAlias, CrudAbility<?> service, QueryRequest request) {
        return plan(moduleAlias).map(plan -> new QueryCompiler(plan.queryDescriptor(),
                plan.querySchema().criteriaComposition()).criteria(request));
    }

    public Optional<Sort[]> querySorts(String moduleAlias, CrudAbility<?> service, QueryRequest request) {
        return plan(moduleAlias).map(plan -> new QueryCompiler(plan.queryDescriptor(),
                plan.querySchema().criteriaComposition()).sorts(request));
    }

    public Optional<FormSchema> formSchema(String moduleAlias, Class<?> modelClass, String resource,
                                            String editorSurface) {
        return plan(moduleAlias).map(plan -> ModuleUiFormSchemaAdapter.formSchema(
                plan.uiDescriptor(), modelClass, resource, editorSurface));
    }

    public List<PageContextBindingDefinition> pageContextBindings(String moduleAlias, PageContextTarget target) {
        return plan(moduleAlias).map(ModuleExecutionPlan::pageContextBindings).orElse(List.of()).stream()
                .filter(binding -> binding.target() == target)
                .toList();
    }

    /** Calculates descriptor-owned summaries which need no domain-specific aggregate implementation. */
    public List<WebListQuerySummaryItem> listQuerySummaries(String moduleAlias, WebQueryRequest request,
                                                          long matchedTotal, CrudAbility<?> service,
                                                          Criteria navigationCriteria,
                                                          ActionExecutionPolicy actionPolicy) {
        ModuleExecutionPlan plan = requirePlan(moduleAlias);
        if (plan.uiDescriptor().page() == null || plan.uiDescriptor().page().list() == null) return List.of();
        Criteria baseCriteria = Criteria.copyOf(navigationCriteria == null ? Criteria.of() : navigationCriteria);
        WebQueryRequest executionRequest = CrudWebRuntimeSupport.withoutWorkspaceExternalValues(request,
                plan.pageContextBindings());
        return listQuerySummaryRuntime.summarize(moduleAlias,
                plan.uiDescriptor().page().list().querySummaries(), request, matchedTotal,
                additionalCriteria -> scopedSummaryCount(moduleAlias, executionRequest, service, baseCriteria,
                        actionPolicy, additionalCriteria), aggregateQuery -> scopedSummaryAggregate(moduleAlias,
                        executionRequest, service, baseCriteria, actionPolicy, aggregateQuery));
    }

    private long scopedSummaryCount(String moduleAlias, WebQueryRequest request, CrudAbility<?> service,
                                    Criteria baseCriteria, ActionExecutionPolicy actionPolicy,
                                    Criteria additionalCriteria) {
        Criteria criteria = Criteria.copyOf(baseCriteria).and(additionalCriteria == null ? Criteria.of() : additionalCriteria);
        Optional<WebPageResponse<Map<String, Object>>> projected = readProjectionService.queryDefaultList(
                moduleAlias, WebQueryRequests.from(request), criteria, PageRequest.of(1, 1), service,
                actionPolicy, RecordReadVisibility.ACTIVE);
        if (projected.isPresent()) return projected.get().total();
        ModuleExecutionPlan plan = requirePlan(moduleAlias);
        Criteria queryCriteria = new QueryCompiler(plan.queryDescriptor(), plan.querySchema().criteriaComposition())
                .criteria(WebQueryRequests.from(request));
        criteria = Criteria.copyOf(queryCriteria).and(criteria);
        if (service instanceof DataScopeAbility<?> scoped) {
            DataScopeCriteriaResult scope = scoped.readScopeByPolicy(actionPolicy, criteria);
            return scoped.withDataScopeTenant(scope, () -> service.count(scope.criteria()));
        }
        return service.count(criteria);
    }

    private List<Map<String, Object>> scopedSummaryAggregate(String moduleAlias, WebQueryRequest request,
                                                               CrudAbility<?> service, Criteria baseCriteria,
                                                               ActionExecutionPolicy actionPolicy,
                                                               AggregateQuery aggregateQuery) {
        return readProjectionService.aggregateDefaultList(moduleAlias, WebQueryRequests.from(request), baseCriteria,
                        service, actionPolicy, RecordReadVisibility.ACTIVE, aggregateQuery)
                .orElseThrow(() -> new UnsupportedOperationException(
                        "aggregate list query summary requires an executable default list projection: " + moduleAlias));
    }

    /** Server-authoritative create/update fields from the compiled execution plan. */
    public List<PageContextBindingDefinition> mutationConstraints(String moduleAlias) {
        return plan(moduleAlias).map(ModuleExecutionPlan::mutationConstraints).orElse(List.of());
    }

    /**
     * Field semantics consumed by the standard-module HTTP adapter.  They are compiled with the
     * execution plan, so request handling never infers wire types from Java reflection.
     */
    public Map<String, FieldValueType> wireFieldTypes(String moduleAlias) {
        ModuleExecutionPlan plan = requirePlan(moduleAlias);
        LinkedHashMap<String, FieldValueType> types = new LinkedHashMap<>(plan.responseWireFieldTypes());
        collectFieldTypes(types, plan.uiDescriptor().defaultEditor());
        if (plan.uiDescriptor().page() != null) {
            ResolvedModulePageDescriptor page = plan.uiDescriptor().page();
            if (page.list() != null) collectFieldTypes(types, page.list().fields());
            if (page.detail() != null) {
                collectFieldTypes(types, page.detail().display());
                collectFieldTypes(types, page.detail().editor());
            }
        }
        plan.uiDescriptor().editorSurfaces().forEach(surface -> collectFieldTypes(types, surface.editor()));
        plan.uiDescriptor().detailRelations().stream()
                .filter(relation -> relation.embeddedField() != null)
                .forEach(relation -> plan.detailRelationWireFieldTypes()
                        .getOrDefault(relation.code(), Map.of())
                        .forEach((fieldName, fieldType) ->
                                types.put(relation.embeddedField() + "." + fieldName, fieldType)));
        return Map.copyOf(types);
    }

    /** Field semantics for one compiled child relation, isolated from similarly named parent fields. */
    public Map<String, FieldValueType> relationWireFieldTypes(String moduleAlias, String relationCode) {
        ModuleExecutionPlan plan = requirePlan(moduleAlias);
        Map<String, FieldValueType> types = plan.detailRelationWireFieldTypes().get(relationCode);
        if (types == null) {
            throw new IllegalArgumentException("unknown detail relation wire facts: " + relationCode);
        }
        return types;
    }

    /** Marks the current HTTP response for managed serialization-time numeric adaptation. */
    public void markWireResponse(String moduleAlias) {
        StaticModuleWebWireValues.markCurrentResponse(wireFieldTypes(moduleAlias));
    }

    /** Marks a managed relation response without leaking child field types into the parent record contract. */
    public void markRelationWireResponse(String moduleAlias, String relationCode) {
        StaticModuleWebWireValues.markCurrentResponse(relationWireFieldTypes(moduleAlias, relationCode));
    }

    /**
     * Executes the compiled plan's default list projection without consulting a controller
     * declaration.  This is the only read-projection entry point for migrated static modules.
     */
    public Optional<WebPageResponse<Map<String, Object>>> queryProjectedDefaultList(
            String moduleAlias,
            QueryRequest request,
            Criteria additionalCriteria,
            PageRequest pageRequest,
            CrudAbility<?> service,
            ActionExecutionPolicy actionPolicy,
            RecordReadVisibility visibility) {
        requirePlan(moduleAlias);
        return readProjectionService.queryDefaultList(moduleAlias, request, additionalCriteria, pageRequest,
                service, actionPolicy, visibility);
    }

    /** Applies the compiled plan's default response projection for a migrated static module. */
    public <T> WebPageResponse<T> projectDefaultList(String moduleAlias,
                                                      WebPageResponse<T> response,
                                                      CrudAbility<?> service) {
        requirePlan(moduleAlias);
        return readProjectionService.projectDefaultList(moduleAlias, response, service);
    }

    private Optional<ModuleExecutionPlan> plan(String moduleAlias) {
        return executionPlans.find(moduleAlias);
    }

    private static void collectFieldTypes(Map<String, FieldValueType> target, ResolvedViewDescriptor view) {
        if (view == null) return;
        view.fields().forEach(field -> {
            if (field.valueType() != null && field.fieldRef().relationCode() == null) {
                target.putIfAbsent(field.fieldRef().fieldName(), field.valueType());
            }
        });
    }
}
