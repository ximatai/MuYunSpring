package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.form.FormAbility;
import net.ximatai.muyun.spring.ability.form.FormSchema;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.platform.module.StaticModuleServiceDeclaration;
import org.springframework.http.HttpStatus;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CrudWeb<T extends EntityContract, S extends CrudAbility<T>>
        extends QueryViewWeb<T, S>, RecordLabelWeb<T>, StaticModuleServiceDeclaration {
    @Override
    default CrudAbility<?> staticModuleService() {
        return service();
    }

    default StaticRecordReadProjectionService staticRecordReadProjectionService() {
        return null;
    }

    /** Optional bridge for migrated controllers. Its presence makes execution-plan facts authoritative. */
    default StandardModuleWebRuntime standardModuleWebRuntime() {
        return null;
    }

    /**
     * Marks a controller that has completed the execution-plan migration.
     *
     * <p>Compatibility controllers may still derive their transport behaviour from a declaration
     * DSL while they are migrated one by one. A migrated controller must instead have both the
     * standard runtime and its compiled plan available; silently taking the compatibility branch
     * would recreate the second request-time source of truth this migration removes.</p>
     */
    default boolean requiresModuleExecutionPlan() {
        return false;
    }

    /** Temporary boundary for controllers explicitly migrating from the old read-projection path. */
    default boolean allowsLegacyReadProjectionCompatibility() {
        return this instanceof LegacyStaticReadProjectionCompatibility;
    }

    private StandardModuleWebRuntime requiredStandardModuleWebRuntime() {
        StandardModuleWebRuntime runtime = standardModuleWebRuntime();
        if (runtime == null) {
            throw new IllegalStateException("migrated module " + webScopeName()
                    + " requires StandardModuleWebRuntime");
        }
        runtime.requirePlan(webScopeName());
        return runtime;
    }

    private StandardModuleWebRuntime executionRuntime() {
        return requiresModuleExecutionPlan() ? requiredStandardModuleWebRuntime() : standardModuleWebRuntime();
    }

    private void requireExecutionPlanAtRequest() {
        if (requiresModuleExecutionPlan()) {
            requiredStandardModuleWebRuntime();
        }
    }

    default List<T> queryListRecords(WebQueryRequest request) {
        requireUnpagedQuerySupported(request);
        if (service() instanceof DataScopeAbility<?>) {
            DataScopeAbility<?> dataScopeAbility = DataScopeAbility.cast(service());
            @SuppressWarnings("unchecked")
            List<T> result = (List<T>) dataScopeAbility.listForAction(
                    PlatformAction.QUERY, queryCriteria(request), querySorts(request));
            return result;
        }
        return service().list(queryCriteria(request), querySorts(request));
    }

    default boolean supportsUnpagedQuery() {
        return false;
    }

    default void requireUnpagedQuerySupported(WebQueryRequest request) {
        if (!supportsUnpagedQuery()) {
            throw new IllegalArgumentException("unpaged query is not supported by " + webScopeName());
        }
        if (request != null && request.page() != null) {
            throw new IllegalArgumentException("unpaged query cannot specify page");
        }
        if (request != null && request.navigationSessionEnabled()) {
            throw new IllegalArgumentException("unpaged query navigation is not supported by " + webScopeName());
        }
    }

    default Criteria queryCriteria(WebQueryRequest request) {
        Criteria workspaceCriteria = navigatorCriteria(request);
        StandardModuleWebRuntime runtime = executionRuntime();
        if (requiresModuleExecutionPlan()) {
            return andCriteria(runtime.queryCriteria(webScopeName(), service(), WebQueryRequests.from(
                    withoutNavigatorExternalValues(request))).orElseThrow(), workspaceCriteria);
        }
        if (runtime != null && runtime.hasPlan(webScopeName())) {
            return andCriteria(runtime.queryCriteria(webScopeName(), service(), WebQueryRequests.from(
                    withoutNavigatorExternalValues(request))).orElseThrow(), workspaceCriteria);
        }
        if (service() instanceof QueryAbility<?> queryAbility) {
            // Navigator values are page-owned bindings, not fields in the source module's query schema.
            // Remove only the declared bindings before compiling the module query; unknown external values
            // remain visible to the compiler and are still rejected.
            Criteria criteria = queryAbility.queryCriteria(WebQueryRequests.from(withoutNavigatorExternalValues(request)));
            return andCriteria(criteria, workspaceCriteria);
        }
        if (request != null && !request.conditions().isEmpty()) {
            throw new IllegalArgumentException("query conditions are not supported by " + webScopeName());
        }
        if (request != null && request.criteria() != null && !request.criteria().isEmpty()) {
            throw new IllegalArgumentException("query criteria are not supported by " + webScopeName());
        }
        return workspaceCriteria;
    }

    private WebQueryRequest withoutNavigatorExternalValues(WebQueryRequest request) {
        if (request == null || request.externalQueryValues().isEmpty()) return request;
        java.util.Set<String> navigatorKeys = pageContextBindings(request.uiConfigId(), PageContextTarget.LIST_QUERY).stream()
                .filter(binding -> binding.source() == PageContextSource.NAVIGATOR)
                .map(PageContextBindingDefinition::targetKey)
                .collect(java.util.stream.Collectors.toSet());
        if (navigatorKeys.isEmpty()) return request;
        Map<String, Object> remaining = new java.util.LinkedHashMap<>(request.externalQueryValues());
        remaining.keySet().removeAll(navigatorKeys);
        return new WebQueryRequest(request.page(), request.unpaged(), request.conditions(), request.criteria(),
                request.queryForm(), request.sorts(), request.uiConfigId(), request.queryTemplateId(), remaining,
                request.navigationSession(), request.quickSearch(), request.quickSearchFields(), request.navigationQueryKey());
    }

    default Sort[] querySorts(WebQueryRequest request) {
        StandardModuleWebRuntime runtime = executionRuntime();
        if (requiresModuleExecutionPlan()) {
            return runtime.querySorts(webScopeName(), service(), WebQueryRequests.from(request)).orElseThrow();
        }
        if (runtime != null && runtime.hasPlan(webScopeName())) {
            return runtime.querySorts(webScopeName(), service(), WebQueryRequests.from(request)).orElseThrow();
        }
        if (service() instanceof QueryAbility<?> queryAbility) {
            Sort[] sorts = queryAbility.querySorts(WebQueryRequests.from(request));
            return sorts == null ? new Sort[0] : sorts;
        }
        if (request != null && !request.sorts().isEmpty()) {
            throw new IllegalArgumentException("query sorts are not supported by " + webScopeName());
        }
        if (service() instanceof SortAbility<?>) {
            return new Sort[]{Sort.asc(PlatformAbilityFields.SORT_FIELD)};
        }
        return new Sort[0];
    }

    @GetMapping("/query/schema")
    @ActionEndpoint(PlatformAction.QUERY)
    default QuerySchema querySchema(@RequestParam(required = false) String uiConfigId) {
        return webScope(() -> {
            StandardModuleWebRuntime runtime = executionRuntime();
            if (requiresModuleExecutionPlan()) {
                return withNavigatorCriteria(runtime.querySchema(webScopeName(), service()).orElseThrow(), uiConfigId);
            }
            if (runtime != null && runtime.hasPlan(webScopeName())) {
                return withNavigatorCriteria(runtime.querySchema(webScopeName(), service()).orElseThrow(), uiConfigId);
            }
            StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
            if (projectionService != null && this instanceof StaticModuleUiContributor contributor
                    && isCurrentModuleUiDefinition(contributor)
                    && projectionService.hasModuleDefinition(contributor.moduleUiDefinition().moduleAlias())) {
                return withNavigatorCriteria(
                        projectionService.querySchema(contributor.moduleUiDefinition().moduleAlias(), service()), uiConfigId);
            }
            if (service() instanceof QueryAbility<?> queryAbility) {
                return withNavigatorCriteria(queryAbility.querySchema(), uiConfigId);
            }
            throw new IllegalArgumentException("query schema is not supported by " + webScopeName());
        });
    }

    private Criteria navigatorCriteria(WebQueryRequest request) {
        Criteria criteria = Criteria.of();
        String uiConfigId = request == null ? null : request.uiConfigId();
        for (PageContextBindingDefinition binding : pageContextBindings(uiConfigId, PageContextTarget.LIST_QUERY)) {
            Object selectedValue = PageContextServerValueResolver.resolve(binding).orElseGet(() ->
                    request == null || request.externalQueryValues() == null ? null
                            : request.externalQueryValues().get(binding.targetKey()));
            if (selectedValue != null) criteria.eq(binding.targetKey(), selectedValue);
        }
        return criteria;
    }

    private QuerySchema withNavigatorCriteria(QuerySchema schema, String uiConfigId) {
        List<PageContextBindingDefinition> bindings = pageContextBindings(uiConfigId, PageContextTarget.LIST_QUERY);
        if (bindings.isEmpty()) return schema;
        List<QuerySchema.ExternalCriteria> externalCriteria = new ArrayList<>(schema.externalCriteria());
        for (PageContextBindingDefinition binding : bindings) {
            if (binding.source() == PageContextSource.SESSION) continue;
            if (externalCriteria.stream().noneMatch(criteria -> binding.targetKey().equals(criteria.key()))) {
                externalCriteria.add(new QuerySchema.ExternalCriteria(binding.targetKey(), "OBJECT", "PAGE_CONTEXT"));
            }
        }
        return new QuerySchema(schema.scopeName(), schema.entityAlias(), schema.quickSearch(), schema.fields(),
                externalCriteria, schema.defaultSorts());
    }

    private List<PageContextBindingDefinition> pageContextBindings(String uiConfigId, PageContextTarget target) {
        StandardModuleWebRuntime runtime = executionRuntime();
        if (requiresModuleExecutionPlan()) {
            return runtime.pageContextBindings(webScopeName(), target);
        }
        if (runtime != null && runtime.hasPlan(webScopeName())) {
            return runtime.pageContextBindings(webScopeName(), target);
        }
        if (!(this instanceof StaticModuleUiContributor contributor) || !isCurrentModuleUiDefinition(contributor)) {
            return List.of();
        }
        ModulePageDefinition page = contributor.moduleUiDefinition().page();
        PageNavigatorDefinition navigator = switch (page) {
            case ListDetailCardPageDefinition card -> card.navigator();
            case FlatManagementPageDefinition flat -> flat.navigator();
            case TreeManagementPageDefinition tree -> tree.navigator();
            case null -> null;
        };
        if (navigator == null) return List.of();
        return navigator.contextBindings().stream().filter(binding -> binding.target() == target).toList();
    }

    private Criteria andCriteria(Criteria first, Criteria second) {
        if (first == null || first.isEmpty()) return second == null ? Criteria.of() : second;
        if (second == null || second.isEmpty()) return first;
        Criteria criteria = Criteria.of();
        criteria.andGroup(first.getRoot());
        criteria.andGroup(second.getRoot());
        return criteria;
    }

    @GetMapping("/form/schema")
    @ActionEndpoint(PlatformAction.VIEW)
    default FormSchema formSchema(@RequestParam(required = false) String resource,
                                  @RequestParam(required = false) String editorSurface) {
        return webScope(() -> {
            StandardModuleWebRuntime runtime = executionRuntime();
            if (requiresModuleExecutionPlan()) {
                String selectedResource = resource == null || resource.isBlank() ? staticContributionResource() : resource;
                return runtime.formSchema(webScopeName(), formSchemaModelClass(), selectedResource, editorSurface)
                        .orElseThrow(() -> new IllegalArgumentException("form schema is not available in execution plan for "
                                + webScopeName()));
            }
            if (runtime != null && runtime.hasPlan(webScopeName())) {
                String selectedResource = resource == null || resource.isBlank() ? staticContributionResource() : resource;
                FormSchema schema = runtime.formSchema(webScopeName(), formSchemaModelClass(), selectedResource,
                        editorSurface).orElse(null);
                if (schema != null) return schema;
            }
            if (this instanceof StaticModuleUiContributor contributor) {
                if (isCurrentModuleUiDefinition(contributor)) {
                    String selectedResource = resource == null || resource.isBlank()
                            ? staticContributionResource() : resource;
                    FormSchema schema = ModuleUiFormSchemaAdapter.formSchema(contributor.moduleUiDefinition(),
                            formSchemaModelClass(), selectedResource, editorSurface);
                    if (schema != null) {
                        return schema;
                    }
                }
            }
            if (service() instanceof FormAbility<?> formAbility) {
                return formAbility.formSchema();
            }
            throw new IllegalArgumentException("form schema is not supported by " + webScopeName());
        });
    }

    private boolean isCurrentModuleUiDefinition(StaticModuleUiContributor contributor) {
        if (contributor.moduleUiDefinition() == null) return false;
        if (webScopeName().equals(contributor.moduleUiDefinition().moduleAlias())) return true;
        PlatformStaticActionContribution contribution = org.springframework.core.annotation.AnnotationUtils
                .findAnnotation(getClass(), PlatformStaticActionContribution.class);
        return contribution != null && contribution.targetModule().equals(contributor.moduleUiDefinition().moduleAlias());
    }

    private Class<?> formSchemaModelClass() {
        Class<?> modelClass = service().modelClass();
        if (modelClass != null) {
            return modelClass;
        }
        return ResolvableType.forClass(CrudWeb.class, getClass()).resolveGeneric(0);
    }

    private String staticContributionResource() {
        PlatformStaticActionContribution contribution = org.springframework.core.annotation.AnnotationUtils
                .findAnnotation(getClass(), PlatformStaticActionContribution.class);
        return contribution == null ? null : contribution.resource();
    }

    @PostMapping("/query")
    @ActionEndpoint(PlatformAction.QUERY)
    @SuppressWarnings("unchecked")
    default WebPageResponse<T> query(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            requireExecutionPlanAtRequest();
            if (request == null || !request.unpagedEnabled()) {
                Optional<WebPageResponse<Map<String, Object>>> projected = queryStaticProjectedList(
                        request, RecordReadVisibility.ACTIVE);
                if (projected.isPresent()) {
                    return (WebPageResponse<T>) (WebPageResponse<?>) projected.get();
                }
            }
            WebPageResponse<T> response;
            if (request != null && request.unpagedEnabled()) {
                List<T> records = WebOutputSupport.records(service(), queryListRecords(request), FieldOutputContext.LIST);
                response = WebPageResponse.fromList(records);
            } else {
                response = WebPageResponse.from(WebOutputSupport.page(service(), queryRecords(request), FieldOutputContext.LIST));
            }
            return projectStaticDefaultList(response);
        });
    }

    default Optional<WebPageResponse<Map<String, Object>>> queryStaticProjectedList(
            WebQueryRequest request, RecordReadVisibility visibility) {
        StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
        if (projectionService == null || visibility == null) {
            return Optional.empty();
        }
        String moduleAlias = moduleAliasForRuntime();
        if (moduleAlias == null) return Optional.empty();
        WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
        if (!allowsLegacyReadProjectionCompatibility()) {
            return projectionService.queryDefaultList(
                    moduleAlias,
                    WebQueryRequests.from(request),
                    navigatorCriteria(request),
                    PageRequest.of(page.pageNum(), page.pageSize()),
                    service(),
                    StaticStandardMutationSupport.actionPolicy(this, visibility.action()),
                    visibility
            );
        }
        return projectionService.queryDefaultList(
                moduleAlias,
                WebQueryRequests.from(request),
                navigatorCriteria(request),
                PageRequest.of(page.pageNum(), page.pageSize()),
                service(),
                StaticStandardMutationSupport.actionPolicy(this, visibility.action()),
                visibility,
                allowsLegacyReadProjectionCompatibility()
        );
    }

    default WebPageResponse<T> projectStaticDefaultList(WebPageResponse<T> response) {
        StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
        if (projectionService == null) {
            return response;
        }
        String moduleAlias = moduleAliasForRuntime();
        if (moduleAlias == null) return response;
        return allowsLegacyReadProjectionCompatibility()
                ? projectionService.projectDefaultList(moduleAlias, response, service(), true)
                : projectionService.projectDefaultList(moduleAlias, response, service());
    }

    private String moduleAliasForRuntime() {
        StandardModuleWebRuntime runtime = executionRuntime();
        if (requiresModuleExecutionPlan()) return webScopeName();
        if (runtime != null && runtime.hasPlan(webScopeName())) return webScopeName();
        if (this instanceof StaticModuleUiContributor contributor) return contributor.moduleUiDefinition().moduleAlias();
        return null;
    }

    @GetMapping("/view/{id}")
    @ActionEndpoint(PlatformAction.VIEW)
    default T view(@PathVariable String id) {
        return webScope(() -> {
            requireExecutionPlanAtRequest();
            return WebOutputSupport.record(service(),
                    StaticStandardMutationSupport.selectForAction(this, PlatformAction.VIEW, id),
                    FieldOutputContext.VIEW);
        });
    }

    @PostMapping("/insert")
    @ActionEndpoint(PlatformAction.CREATE)
    @StandardMutation(StandardMutationKind.CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    default T insert(@RequestBody T record) {
        return MutationTenantScopeExecutor.forCreate(this, record, () -> webScope(() -> {
            requireExecutionPlanAtRequest();
            List<PageContextBindingDefinition> mutationConstraints =
                    requiresModuleExecutionPlan()
                            ? requiredStandardModuleWebRuntime().mutationConstraints(webScopeName())
                            : pageContextBindings(null, PageContextTarget.MUTATION_CONSTRAINT);
            if (!mutationConstraints.isEmpty()) {
                PageContextMutationConstraints.applyForCreate(record, mutationConstraints);
            }
            String id = service().insert(record);
            T saved = WebOutputSupport.record(service(), service().select(id), FieldOutputContext.VIEW);
            StandardMutationResultSupport.created(this, id, recordLabel(saved));
            return saved;
        }));
    }

    @PostMapping("/update/{id}")
    @ActionEndpoint(PlatformAction.UPDATE)
    @StandardMutation(StandardMutationKind.UPDATE)
    @Transactional
    default T update(@PathVariable String id, @RequestBody T record) {
        record.setId(id);
        return MutationTenantScopeExecutor.forUpdate(this, id, record, () -> webScope(() -> {
            requireExecutionPlanAtRequest();
            StaticStandardMutationSupport.requireDataScopeRecord(this, PlatformAction.UPDATE, id);
            List<PageContextBindingDefinition> mutationConstraints =
                    requiresModuleExecutionPlan()
                            ? requiredStandardModuleWebRuntime().mutationConstraints(webScopeName())
                            : pageContextBindings(null, PageContextTarget.MUTATION_CONSTRAINT);
            if (!mutationConstraints.isEmpty()) {
                PageContextMutationConstraints.applyForUpdate(record, service().select(id), mutationConstraints);
            }
            service().update(record);
            T saved = WebOutputSupport.record(service(),
                    StaticStandardMutationSupport.selectForAction(this, PlatformAction.VIEW, id),
                    FieldOutputContext.VIEW);
            StandardMutationResultSupport.updated(this, id, recordLabel(saved));
            return saved;
        }));
    }

    @PostMapping("/delete/{id}")
    @ActionEndpoint(PlatformAction.DELETE)
    @StandardMutation(StandardMutationKind.DELETE)
    default int delete(@PathVariable String id, @RequestBody RecordActionWebRequest request) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            requireExecutionPlanAtRequest();
            StaticStandardMutationSupport.requireDataScopeRecord(this, PlatformAction.DELETE, id);
            T existing = service().select(id);
            return StandardMutationResultSupport.deleted(this, id, recordLabel(existing),
                    () -> service().delete(id, request.version()));
        }));
    }
}
