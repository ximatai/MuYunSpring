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
        return CrudWebRuntimeSupport.requiredRuntime(this);
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
        return CrudWebRuntimeSupport.queryCriteria(this, request);
    }

    default Sort[] querySorts(WebQueryRequest request) {
        return CrudWebRuntimeSupport.querySorts(this, request);
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
        if (visibility == null) {
            return Optional.empty();
        }
        String moduleAlias = moduleAliasForRuntime();
        if (moduleAlias == null) return Optional.empty();
        WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
        StandardModuleWebRuntime runtime = executionRuntime();
        if (requiresModuleExecutionPlan() || runtime != null && runtime.hasPlan(webScopeName())) {
            return runtime.queryProjectedDefaultList(
                    moduleAlias,
                    WebQueryRequests.from(request),
                    CrudWebRuntimeSupport.navigatorCriteria(this, request),
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
        return projectionService.queryDefaultList(
                moduleAlias,
                WebQueryRequests.from(request),
                CrudWebRuntimeSupport.navigatorCriteria(this, request),
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
                            : CrudWebRuntimeSupport.pageContextBindings(this, null, PageContextTarget.MUTATION_CONSTRAINT);
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
                            : CrudWebRuntimeSupport.pageContextBindings(this, null, PageContextTarget.MUTATION_CONSTRAINT);
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
