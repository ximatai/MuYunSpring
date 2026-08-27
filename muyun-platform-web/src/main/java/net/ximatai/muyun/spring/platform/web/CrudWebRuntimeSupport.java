package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.form.FormAbility;
import net.ximatai.muyun.spring.ability.form.FormSchema;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import org.springframework.core.ResolvableType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution-path adapter for the standard CRUD Web contract.
 *
 * <p>This keeps {@link CrudWeb} limited to endpoint declaration and service delegation.  The
 * adapter owns the choice between a compiled module plan and the deliberately retained legacy
 * declaration path.  A controller that declares {@code requiresModuleExecutionPlan()} never
 * reaches the latter path.</p>
 */
final class CrudWebRuntimeSupport {
    private CrudWebRuntimeSupport() {
    }

    static StandardModuleWebRuntime requiredRuntime(CrudWeb<?, ?> controller) {
        StandardModuleWebRuntime runtime = controller.standardModuleWebRuntime();
        if (runtime == null) {
            throw new IllegalStateException("migrated module " + controller.webScopeName()
                    + " requires StandardModuleWebRuntime");
        }
        runtime.requirePlan(controller.webScopeName());
        return runtime;
    }

    static StandardModuleWebRuntime executionRuntime(CrudWeb<?, ?> controller) {
        return controller.requiresModuleExecutionPlan() ? requiredRuntime(controller)
                : controller.standardModuleWebRuntime();
    }

    static void requireExecutionPlan(CrudWeb<?, ?> controller) {
        if (controller.requiresModuleExecutionPlan()) {
            requiredRuntime(controller);
        }
    }

    static Criteria queryCriteria(CrudWeb<?, ?> controller, WebQueryRequest request) {
        Criteria workspaceCriteria = navigatorCriteria(controller, request);
        StandardModuleWebRuntime runtime = executionRuntime(controller);
        if (controller.requiresModuleExecutionPlan() || runtime != null && runtime.hasPlan(controller.webScopeName())) {
            // A static service without QueryAbility compiles an explicit empty query contract.
            // Keep the public rejection semantics instead of letting normalization silently drop
            // caller supplied filters before QueryCompiler sees them.
            QuerySchema schema = runtime.querySchema(controller.webScopeName(), controller.service()).orElseThrow();
            if (schema.fields().isEmpty() && request != null && request.criteria() != null && !request.criteria().isEmpty()) {
                throw new IllegalArgumentException("query criteria are not supported by " + controller.webScopeName());
            }
            if (schema.fields().isEmpty() && request != null && !request.conditions().isEmpty()) {
                throw new IllegalArgumentException("query conditions are not supported by " + controller.webScopeName());
            }
            return andCriteria(runtime.queryCriteria(controller.webScopeName(), controller.service(),
                    WebQueryRequests.from(withoutWorkspaceExternalValues(controller, request))).orElseThrow(), workspaceCriteria);
        }
        if (controller.service() instanceof QueryAbility<?> queryAbility) {
            return andCriteria(queryAbility.queryCriteria(WebQueryRequests.from(withoutWorkspaceExternalValues(controller, request))),
                    workspaceCriteria);
        }
        // A structured criteria payload is semantically more specific than its compatibility
        // condition projection. Preserve the established public error contract when both views
        // are populated by request normalization.
        if (request != null && request.criteria() != null && !request.criteria().isEmpty()) {
            throw new IllegalArgumentException("query criteria are not supported by " + controller.webScopeName());
        }
        if (request != null && !request.conditions().isEmpty()) {
            throw new IllegalArgumentException("query conditions are not supported by " + controller.webScopeName());
        }
        return workspaceCriteria;
    }

    static Sort[] querySorts(CrudWeb<?, ?> controller, WebQueryRequest request) {
        StandardModuleWebRuntime runtime = executionRuntime(controller);
        if (controller.requiresModuleExecutionPlan() || runtime != null && runtime.hasPlan(controller.webScopeName())) {
            return runtime.querySorts(controller.webScopeName(), controller.service(), WebQueryRequests.from(request)).orElseThrow();
        }
        if (controller.service() instanceof QueryAbility<?> queryAbility) {
            Sort[] sorts = queryAbility.querySorts(WebQueryRequests.from(request));
            return sorts == null ? new Sort[0] : sorts;
        }
        if (request != null && !request.sorts().isEmpty()) {
            throw new IllegalArgumentException("query sorts are not supported by " + controller.webScopeName());
        }
        return controller.service() instanceof SortAbility<?> ? new Sort[]{Sort.asc(PlatformAbilityFields.SORT_FIELD)} : new Sort[0];
    }

    static QuerySchema querySchema(CrudWeb<?, ?> controller, String uiConfigId) {
        StandardModuleWebRuntime runtime = executionRuntime(controller);
        if (controller.requiresModuleExecutionPlan() || runtime != null && runtime.hasPlan(controller.webScopeName())) {
            return withNavigatorCriteria(controller,
                    runtime.querySchema(controller.webScopeName(), controller.service()).orElseThrow(), uiConfigId);
        }
        StaticRecordReadProjectionService projectionService = controller.staticRecordReadProjectionService();
        if (projectionService != null && controller instanceof StaticModuleUiContributor contributor
                && isCurrentModuleUiDefinition(controller, contributor)
                && projectionService.hasModuleDefinition(contributor.moduleUiDefinition().moduleAlias())) {
            return withNavigatorCriteria(controller,
                    projectionService.querySchema(contributor.moduleUiDefinition().moduleAlias(), controller.service()), uiConfigId);
        }
        if (controller.service() instanceof QueryAbility<?> queryAbility) {
            return withNavigatorCriteria(controller, queryAbility.querySchema(), uiConfigId);
        }
        throw new IllegalArgumentException("query schema is not supported by " + controller.webScopeName());
    }

    static FormSchema formSchema(CrudWeb<?, ?> controller, String resource, String editorSurface) {
        StandardModuleWebRuntime runtime = executionRuntime(controller);
        String selectedResource = resource == null || resource.isBlank() ? staticContributionResource(controller) : resource;
        if (controller.requiresModuleExecutionPlan()) {
            return runtime.formSchema(controller.webScopeName(), formSchemaModelClass(controller), selectedResource, editorSurface)
                    .orElseThrow(() -> new IllegalArgumentException("form schema is not available in execution plan for " + controller.webScopeName()));
        }
        if (runtime != null && runtime.hasPlan(controller.webScopeName())) {
            FormSchema schema = runtime.formSchema(controller.webScopeName(), formSchemaModelClass(controller), selectedResource, editorSurface).orElse(null);
            if (schema != null) return schema;
        }
        if (controller instanceof StaticModuleUiContributor contributor && isCurrentModuleUiDefinition(controller, contributor)) {
            FormSchema schema = ModuleUiFormSchemaAdapter.formSchema(contributor.moduleUiDefinition(),
                    formSchemaModelClass(controller), selectedResource, editorSurface);
            if (schema != null) return schema;
        }
        if (controller.service() instanceof FormAbility<?> formAbility) return formAbility.formSchema();
        throw new IllegalArgumentException("form schema is not supported by " + controller.webScopeName());
    }

    static List<PageContextBindingDefinition> pageContextBindings(CrudWeb<?, ?> controller,
                                                                    String uiConfigId,
                                                                    PageContextTarget target) {
        StandardModuleWebRuntime runtime = executionRuntime(controller);
        if (controller.requiresModuleExecutionPlan() || runtime != null && runtime.hasPlan(controller.webScopeName())) {
            return runtime.pageContextBindings(controller.webScopeName(), target);
        }
        if (!(controller instanceof StaticModuleUiContributor contributor) || !isCurrentModuleUiDefinition(controller, contributor)) {
            return selectionBindings(controller, target);
        }
        ModulePageDefinition page = contributor.moduleUiDefinition().page();
        PageNavigatorDefinition navigator = switch (page) {
            case ListDetailCardPageDefinition card -> card.navigator();
            case FlatManagementPageDefinition flat -> flat.navigator();
            case TreeManagementPageDefinition tree -> tree.navigator();
            case null -> null;
        };
        return mergeSelectionBindings(navigator == null ? List.of()
                : navigator.contextBindings().stream().filter(binding -> binding.target() == target).toList(), controller, target);
    }

    static Criteria navigatorCriteria(CrudWeb<?, ?> controller, WebQueryRequest request) {
        String uiConfigId = request == null ? null : request.uiConfigId();
        return PageContextScopePolicy.criteria(
                pageContextBindings(controller, uiConfigId, PageContextTarget.LIST_QUERY),
                request == null ? Map.of() : request.externalQueryValues(), false,
                controller.webScopeName(), net.ximatai.muyun.spring.common.platform.PlatformAction.QUERY,
                controller.pageSelectionContextResolvers());
    }

    static List<PageContextBindingDefinition> recordScopeBindings(CrudWeb<?, ?> controller) {
        return PageContextScopePolicy.recordScopeBindings(
                pageContextBindings(controller, null, PageContextTarget.LIST_QUERY));
    }

    static List<PageContextBindingDefinition> mutationConstraints(CrudWeb<?, ?> controller) {
        StandardModuleWebRuntime runtime = executionRuntime(controller);
        if (controller.requiresModuleExecutionPlan()) {
            return requiredRuntime(controller).mutationConstraints(controller.webScopeName());
        }
        if (runtime != null && runtime.hasPlan(controller.webScopeName())) {
            return runtime.mutationConstraints(controller.webScopeName());
        }
        return pageContextBindings(controller, null, PageContextTarget.MUTATION_CONSTRAINT);
    }

    /**
     * Resolves the tenant context before a create mutation only when the page explicitly binds a
     * trusted selection to {@code tenantId}. An explicit null keeps a platform selection in
     * system scope and prevents a later record-derived resolver from widening it.
     */
    static java.util.Optional<ResolvedSelectionTenantScope> resolvedSelectionTenantScopeForCreate(
            CrudWeb<?, ?> controller) {
        List<PageContextBindingDefinition> bindings = mutationConstraints(controller).stream()
                .filter(binding -> binding.source() == PageContextSource.RESOLVED_SELECTION)
                .filter(binding -> StandardEntitySchema.TENANT_ID_FIELD.equals(binding.targetKey()))
                .toList();
        if (bindings.isEmpty()) return java.util.Optional.empty();
        if (bindings.size() != 1) {
            throw new IllegalStateException("create page selection may declare tenantId only once: "
                    + controller.webScopeName());
        }
        PageContextValue value = PageContextScopePolicy.requiredMutationValue(bindings.getFirst(),
                controller.webScopeName(), PlatformAction.CREATE, controller.pageSelectionContextResolvers());
        Object tenantId = value.value();
        if (tenantId == null) return java.util.Optional.of(new ResolvedSelectionTenantScope(null));
        if (!(tenantId instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("resolved page selection tenantId must be a non-blank string or null: "
                    + controller.webScopeName());
        }
        return java.util.Optional.of(new ResolvedSelectionTenantScope(text));
    }

    private static List<PageContextBindingDefinition> selectionBindings(CrudWeb<?, ?> controller,
                                                                          PageContextTarget target) {
        return controller.pageSelectionContextBindings().stream().filter(binding -> binding.target() == target).toList();
    }

    record ResolvedSelectionTenantScope(String tenantId) {
    }

    private static List<PageContextBindingDefinition> mergeSelectionBindings(List<PageContextBindingDefinition> bindings,
                                                                               CrudWeb<?, ?> controller,
                                                                               PageContextTarget target) {
        List<PageContextBindingDefinition> selection = selectionBindings(controller, target);
        if (selection.isEmpty()) return bindings;
        List<PageContextBindingDefinition> merged = new ArrayList<>(bindings);
        merged.addAll(selection);
        return List.copyOf(merged);
    }

    /**
     * Browser external values may provide ordinary query filters only. Navigator values and
     * opaque-selection targets are resolved by their own page-context contracts and must not be
     * compiled a second time from the request body.
     */
    private static WebQueryRequest withoutWorkspaceExternalValues(CrudWeb<?, ?> controller, WebQueryRequest request) {
        if (request == null || request.externalQueryValues().isEmpty()) return request;
        Set<String> workspaceKeys = pageContextBindings(controller, request.uiConfigId(), PageContextTarget.LIST_QUERY).stream()
                .filter(binding -> binding.source() == PageContextSource.NAVIGATOR
                        || binding.source() == PageContextSource.RESOLVED_SELECTION)
                .map(PageContextBindingDefinition::targetKey).collect(java.util.stream.Collectors.toSet());
        if (workspaceKeys.isEmpty()) return request;
        Map<String, Object> remaining = new LinkedHashMap<>(request.externalQueryValues());
        remaining.keySet().removeAll(workspaceKeys);
        return new WebQueryRequest(request.page(), request.unpaged(), request.conditions(), request.criteria(), request.queryForm(),
                request.sorts(), request.uiConfigId(), request.queryTemplateId(), remaining, request.navigationSession(),
                request.quickSearch(), request.quickSearchFields(), request.navigationQueryKey());
    }

    private static QuerySchema withNavigatorCriteria(CrudWeb<?, ?> controller, QuerySchema schema, String uiConfigId) {
        List<PageContextBindingDefinition> bindings = pageContextBindings(controller, uiConfigId, PageContextTarget.LIST_QUERY);
        if (bindings.isEmpty()) return schema;
        List<QuerySchema.ExternalCriteria> external = new ArrayList<>(schema.externalCriteria());
        for (PageContextBindingDefinition binding : bindings) {
            if (binding.source() != PageContextSource.SESSION && external.stream().noneMatch(item -> binding.targetKey().equals(item.key()))) {
                external.add(new QuerySchema.ExternalCriteria(binding.targetKey(), "OBJECT", "PAGE_CONTEXT"));
            }
        }
        return new QuerySchema(schema.scopeName(), schema.entityAlias(), schema.quickSearch(), schema.fields(), external, schema.defaultSorts());
    }

    private static boolean isCurrentModuleUiDefinition(CrudWeb<?, ?> controller, StaticModuleUiContributor contributor) {
        if (contributor.moduleUiDefinition() == null) return false;
        if (controller.webScopeName().equals(contributor.moduleUiDefinition().moduleAlias())) return true;
        PlatformStaticActionContribution contribution = org.springframework.core.annotation.AnnotationUtils
                .findAnnotation(controller.getClass(), PlatformStaticActionContribution.class);
        return contribution != null && contribution.targetModule().equals(contributor.moduleUiDefinition().moduleAlias());
    }

    private static Class<?> formSchemaModelClass(CrudWeb<?, ?> controller) {
        Class<?> modelClass = controller.service().modelClass();
        return modelClass != null ? modelClass : ResolvableType.forClass(CrudWeb.class, controller.getClass()).resolveGeneric(0);
    }

    private static String staticContributionResource(CrudWeb<?, ?> controller) {
        PlatformStaticActionContribution contribution = org.springframework.core.annotation.AnnotationUtils
                .findAnnotation(controller.getClass(), PlatformStaticActionContribution.class);
        return contribution == null ? null : contribution.resource();
    }

    private static Criteria andCriteria(Criteria first, Criteria second) {
        if (first == null || first.isEmpty()) return second == null ? Criteria.of() : second;
        if (second == null || second.isEmpty()) return first;
        Criteria criteria = Criteria.of();
        criteria.andGroup(first.getRoot());
        criteria.andGroup(second.getRoot());
        return criteria;
    }
}
