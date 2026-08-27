package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.form.FormSchema;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.platform.module.StaticModuleServiceDeclaration;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestContextHolder;

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

    /**
     * Optional business policy for a server-validated menu entry. A module can expose more than
     * one menu entry without inventing a second CRUD module or duplicating its endpoints.
     */
    default ModulePageEntryPolicy<T> modulePageEntryPolicy() {
        return null;
    }

    /**
     * Required navigator list bindings scope every standard record operation, not only the list.
     * Dynamic controllers override this with their installed execution plan; static controllers
     * receive it from their compiled declaration through the standard runtime adapter.
     */
    default List<PageContextBindingDefinition> recordScopeBindings() {
        return CrudWebRuntimeSupport.recordScopeBindings(this);
    }

    /** Business-owned resolvers for opaque, server-authorized page selections. */
    default PageSelectionContextResolverRegistry pageSelectionContextResolvers() {
        return new PageSelectionContextResolverRegistry(List.of());
    }

    /** Additional static bindings contributed by a business selection extension. */
    default List<PageContextBindingDefinition> pageSelectionContextBindings() {
        return List.of();
    }

    private StandardModuleWebRuntime executionRuntime() {
        return CrudWebRuntimeSupport.executionRuntime(this);
    }

    private void requireExecutionPlanAtRequest() {
        CrudWebRuntimeSupport.requireExecutionPlan(this);
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
        Criteria criteria = CrudWebRuntimeSupport.queryCriteria(this, request);
        applyMenuEntryQueryCriteria(criteria);
        return criteria;
    }

    default Sort[] querySorts(WebQueryRequest request) {
        return CrudWebRuntimeSupport.querySorts(this, request);
    }

    default WebPageResponse<T> attachListQuerySummaries(WebQueryRequest request, WebPageResponse<T> response) {
        if (response == null) return null;
        List<WebListQuerySummaryItem> items = new java.util.ArrayList<>();
        String moduleAlias = moduleAliasForRuntime();
        StandardModuleWebRuntime runtime = executionRuntime();
        if (moduleAlias != null && runtime != null && runtime.hasPlan(moduleAlias)) {
            Criteria summaryCriteria = CrudWebRuntimeSupport.navigatorCriteria(this, request);
            applyMenuEntryQueryCriteria(summaryCriteria);
            items.addAll(runtime.listQuerySummaries(moduleAlias, request, response.total(), service(),
                    summaryCriteria,
                    StaticStandardMutationSupport.actionPolicy(this, PlatformAction.QUERY)));
        }
        if (items.stream().map(WebListQuerySummaryItem::key).distinct().count() != items.size()) {
            throw new IllegalStateException("duplicate list query summary result key: " + webScopeName());
        }
        return response.withSummaries(items);
    }

    @GetMapping("/query/schema")
    @ActionEndpoint(PlatformAction.QUERY)
    default QuerySchema querySchema(@RequestParam(required = false) String uiConfigId) {
        return webScope(() -> CrudWebRuntimeSupport.querySchema(this, uiConfigId));
    }

    @GetMapping("/form/schema")
    @ActionEndpoint(PlatformAction.VIEW)
    default FormSchema formSchema(@RequestParam(required = false) String resource,
                                  @RequestParam(required = false) String editorSurface) {
        return webScope(() -> CrudWebRuntimeSupport.formSchema(this, resource, editorSurface));
    }

    /**
     * Resolves display defaults for an opaque page selection under CREATE authorization.
     * The returned values are convenience data for the form only; insert resolves the selection
     * independently and remains the sole write authority.
     */
    @GetMapping("/page-context/form-defaults")
    @ActionEndpoint(PlatformAction.CREATE)
    default Map<String, Object> pageContextFormDefaults() {
        return webScope(() -> PageContextScopePolicy.resolvedSelectionValues(
                pageSelectionContextBindings(), PageContextTarget.FORM_DEFAULT, webScopeName(),
                PlatformAction.CREATE, pageSelectionContextResolvers()));
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
                    return attachListQuerySummaries(request,
                            (WebPageResponse<T>) (WebPageResponse<?>) standardWirePage(projected.get()));
                }
            }
            WebPageResponse<T> response;
            if (request != null && request.unpagedEnabled()) {
                List<T> records = WebOutputSupport.records(service(), queryListRecords(request), FieldOutputContext.LIST);
                response = WebPageResponse.fromList(records);
            } else {
                response = WebPageResponse.from(WebOutputSupport.page(service(), queryRecords(request), FieldOutputContext.LIST));
            }
            return attachListQuerySummaries(request,
                    (WebPageResponse<T>) standardWirePage(projectStaticDefaultList(response)));
        });
    }

    default Optional<WebPageResponse<Map<String, Object>>> queryStaticProjectedList(
            WebQueryRequest request, RecordReadVisibility visibility) {
        if (visibility == null) {
            return Optional.empty();
        }
        String moduleAlias = moduleAliasForRuntime();
        if (moduleAlias == null) return Optional.empty();
        WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
        StandardModuleWebRuntime runtime = executionRuntime();
        if (requiresModuleExecutionPlan() || runtime != null && runtime.hasPlan(webScopeName())) {
            Criteria navigationCriteria = CrudWebRuntimeSupport.navigatorCriteria(this, request);
            applyMenuEntryQueryCriteria(navigationCriteria);
            return runtime.queryProjectedDefaultList(
                    moduleAlias,
                    WebQueryRequests.from(request),
                    navigationCriteria,
                    PageRequest.of(page.pageNum(), page.pageSize()),
                    service(),
                    StaticStandardMutationSupport.actionPolicy(this, visibility.action()),
                    visibility
            );
        }
        if (!allowsLegacyReadProjectionCompatibility()) {
            return Optional.empty();
        }
        StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
        if (projectionService == null) return Optional.empty();
        Criteria navigationCriteria = CrudWebRuntimeSupport.navigatorCriteria(this, request);
        applyMenuEntryQueryCriteria(navigationCriteria);
        return projectionService.queryDefaultList(
                moduleAlias,
                WebQueryRequests.from(request),
                navigationCriteria,
                PageRequest.of(page.pageNum(), page.pageSize()),
                service(),
                StaticStandardMutationSupport.actionPolicy(this, visibility.action()),
                visibility,
                allowsLegacyReadProjectionCompatibility()
        );
    }

    default WebPageResponse<T> projectStaticDefaultList(WebPageResponse<T> response) {
        String moduleAlias = moduleAliasForRuntime();
        if (moduleAlias == null) return response;
        StandardModuleWebRuntime runtime = executionRuntime();
        if (requiresModuleExecutionPlan() || runtime != null && runtime.hasPlan(webScopeName())) {
            return runtime.projectDefaultList(moduleAlias, response, service());
        }
        if (!allowsLegacyReadProjectionCompatibility()) return response;
        StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
        return projectionService == null ? response
                : projectionService.projectDefaultList(moduleAlias, response, service(), true);
    }

    private String moduleAliasForRuntime() {
        StandardModuleWebRuntime runtime = executionRuntime();
        if (requiresModuleExecutionPlan()) return webScopeName();
        if (runtime != null && runtime.hasPlan(webScopeName())) return webScopeName();
        if (this instanceof StaticModuleUiContributor contributor) return contributor.moduleUiDefinition().moduleAlias();
        return null;
    }

    private WebPageResponse<?> standardWirePage(WebPageResponse<?> response) {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return response;
        }
        StandardModuleWebRuntime runtime = executionRuntime();
        String moduleAlias = moduleAliasForRuntime();
        if (runtime == null || moduleAlias == null || !runtime.hasPlan(moduleAlias)) {
            return response;
        }
        runtime.markWireResponse(moduleAlias);
        return response;
    }

    @SuppressWarnings("unchecked")
    private T standardWireRecord(T record) {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return record;
        }
        StandardModuleWebRuntime runtime = executionRuntime();
        String moduleAlias = moduleAliasForRuntime();
        if (runtime == null || moduleAlias == null || !runtime.hasPlan(moduleAlias)) {
            return record;
        }
        runtime.markWireResponse(moduleAlias);
        return record;
    }

    @GetMapping("/view/{id}")
    @ActionEndpoint(PlatformAction.VIEW)
    default T view(@PathVariable String id) {
        return webScope(() -> {
            requireExecutionPlanAtRequest();
            T record = RecordReadSupport.requireVisible(webScopeName(), id,
                    StaticStandardMutationSupport.selectForAction(this, PlatformAction.VIEW, id));
            PageContextScopePolicy.requireRecordInScope(record, recordScopeBindings(), webScopeName(),
                    PlatformAction.VIEW, pageSelectionContextResolvers());
            requireMenuEntryRecord(PlatformAction.VIEW, record);
            return standardWireRecord(WebOutputSupport.record(service(), record,
                    FieldOutputContext.VIEW));
        });
    }

    @PostMapping("/insert")
    @ActionEndpoint(PlatformAction.CREATE)
    @StandardMutation(StandardMutationKind.CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    default T insert(@RequestBody T record) {
        java.util.function.Supplier<T> insert = () -> webScope(() -> {
            requireExecutionPlanAtRequest();
            requireMenuEntryAction(PlatformAction.CREATE);
            PageContextScopePolicy.applyForCreate(record, recordScopeBindings(), webScopeName(),
                    PlatformAction.CREATE, pageSelectionContextResolvers());
            List<PageContextBindingDefinition> mutationConstraints = CrudWebRuntimeSupport.mutationConstraints(this);
            if (!mutationConstraints.isEmpty()) {
                PageContextMutationConstraints.applyForCreate(record, mutationConstraints, webScopeName(),
                        PlatformAction.CREATE, pageSelectionContextResolvers());
            }
            String id = service().insert(record);
            T saved = WebOutputSupport.record(service(), service().select(id), FieldOutputContext.VIEW);
            StandardMutationResultSupport.created(this, id, recordLabel(saved));
            return standardWireRecord(saved);
        });
        java.util.Optional<CrudWebRuntimeSupport.ResolvedSelectionTenantScope> selectionTenantScope =
                CrudWebRuntimeSupport.resolvedSelectionTenantScopeForCreate(this);
        return selectionTenantScope
                .map(scope -> MutationTenantScopeExecutor.forAuthoritativeTenantScope(scope.tenantId(), insert))
                .orElseGet(() -> MutationTenantScopeExecutor.forCreate(this, record, insert));
    }

    @PostMapping("/update/{id}")
    @ActionEndpoint(PlatformAction.UPDATE)
    @StandardMutation(StandardMutationKind.UPDATE)
    @Transactional
    default T update(@PathVariable String id, @RequestBody T record) {
        record.setId(id);
        return MutationTenantScopeExecutor.forUpdate(this, id, record, () -> webScope(() -> {
            requireExecutionPlanAtRequest();
            requireMenuEntryAction(PlatformAction.UPDATE);
            StaticStandardMutationSupport.requireDataScopeRecord(this, PlatformAction.UPDATE, id);
            T existing = service().select(id);
            PageContextScopePolicy.requireRecordInScope(existing, recordScopeBindings(), webScopeName(),
                    PlatformAction.UPDATE, pageSelectionContextResolvers());
            PageContextScopePolicy.applyForCreate(record, recordScopeBindings(), webScopeName(),
                    PlatformAction.UPDATE, pageSelectionContextResolvers());
            if (hasActiveMenuEntryPolicy()) {
                requireMenuEntryRecord(PlatformAction.UPDATE, existing);
            }
            List<PageContextBindingDefinition> mutationConstraints = CrudWebRuntimeSupport.mutationConstraints(this);
            if (!mutationConstraints.isEmpty()) {
                PageContextMutationConstraints.applyForUpdate(record, existing, mutationConstraints, webScopeName(),
                        PlatformAction.UPDATE, pageSelectionContextResolvers());
            }
            service().update(record);
            T saved = WebOutputSupport.record(service(),
                    StaticStandardMutationSupport.selectForAction(this, PlatformAction.VIEW, id),
                    FieldOutputContext.VIEW);
            StandardMutationResultSupport.updated(this, id, recordLabel(saved));
            return standardWireRecord(saved);
        }));
    }

    @PostMapping("/delete/{id}")
    @ActionEndpoint(PlatformAction.DELETE)
    @StandardMutation(StandardMutationKind.DELETE)
    default int delete(@PathVariable String id, @RequestBody RecordActionWebRequest request) {
        return MutationTenantScopeExecutor.forExistingRecord(this, id, () -> webScope(() -> {
            requireExecutionPlanAtRequest();
            requireMenuEntryAction(PlatformAction.DELETE);
            StaticStandardMutationSupport.requireDataScopeRecord(this, PlatformAction.DELETE, id);
            T existing = service().select(id);
            PageContextScopePolicy.requireRecordInScope(existing, recordScopeBindings(), webScopeName(),
                    PlatformAction.DELETE, pageSelectionContextResolvers());
            requireMenuEntryRecord(PlatformAction.DELETE, existing);
            return StandardMutationResultSupport.deleted(this, id, recordLabel(existing),
                    () -> service().delete(id, request.version()));
        }));
    }

    /** Allows custom endpoints to reuse the same entry restriction boundary as standard CRUD. */
    default void requireMenuEntryAction(PlatformAction action) {
        menuEntryPolicyContext().ifPresent(context -> context.policy().requireAction(context.entry(), action));
    }

    /** Allows custom record endpoints to reuse the same entry restriction boundary as standard CRUD. */
    default void requireMenuEntryRecord(PlatformAction action, T record) {
        menuEntryPolicyContext().ifPresent(context -> context.policy().requireRecord(context.entry(), action, record));
    }

    /**
     * Applies the fixed scope of the server-validated page entry to a custom list endpoint.
     * Custom endpoints own their business query, while the shared CRUD contract owns the entry
     * boundary so that a secondary page entry cannot widen its records through a side endpoint.
     */
    default void applyMenuEntryQueryCriteria(Criteria criteria) {
        menuEntryPolicyContext().ifPresent(context -> context.policy().appendQueryCriteria(context.entry(), criteria));
    }

    /** Whether the current request entered this module through a policy-owned menu entry. */
    default boolean hasActiveMenuEntryPolicy() {
        return menuEntryPolicyContext().isPresent();
    }

    private Optional<MenuEntryPolicyContext<T>> menuEntryPolicyContext() {
        ModulePageEntryPolicy<T> policy = modulePageEntryPolicy();
        if (policy == null) {
            return Optional.empty();
        }
        return MenuEntryRequestContext.current()
                .filter(policy::supports)
                .map(entry -> new MenuEntryPolicyContext<>(entry, policy));
    }

    record MenuEntryPolicyContext<T extends EntityContract>(
            MenuEntryRequestContext entry,
            ModulePageEntryPolicy<T> policy
    ) {
    }
}
