package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.platform.module.StaticModuleServiceDeclaration;
import net.ximatai.muyun.spring.web.*;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;

/** Read-only static delivery using the same compiled query, navigation and projection runtime as CRUD. */
public interface StaticQueryViewWeb<T extends EntityContract, S extends CrudAbility<T>>
        extends QueryViewWeb<T, S>, StaticModuleServiceDeclaration {
    @Override
    default CrudAbility<?> staticModuleService() {
        return service();
    }

    /** Installed by {@link StaticModuleWebControllerAdapter}; no mutation endpoints are inherited. */
    default StandardModuleWebRuntime standardModuleWebRuntime() {
        return null;
    }

    /** Business-owned resolvers for opaque, server-authorized page selections. */
    default PageSelectionContextResolverRegistry pageSelectionContextResolvers() {
        return new PageSelectionContextResolverRegistry(List.of());
    }

    private StandardModuleWebRuntime runtime() {
        StandardModuleWebRuntime runtime = standardModuleWebRuntime();
        if (runtime == null) throw new IllegalStateException("static query module requires StandardModuleWebRuntime: " + webScopeName());
        runtime.requirePlan(webScopeName());
        return runtime;
    }

    @GetMapping("/query/schema")
    @ActionEndpoint(PlatformAction.QUERY)
    @ModuleDiscoveryEndpoint
    default QuerySchema querySchema(@RequestParam(required = false) String uiConfigId) {
        return runtime().querySchema(webScopeName(), service()).orElseThrow();
    }

    @Override
    default Criteria queryCriteria(WebQueryRequest request) {
        Criteria criteria = runtime().queryCriteria(webScopeName(), service(),
                WebQueryRequests.from(executionRequest(request))).orElseThrow();
        return Criteria.copyOf(criteria).and(navigatorCriteria(request));
    }

    @Override
    default Sort[] querySorts(WebQueryRequest request) {
        return runtime().querySorts(webScopeName(), service(), WebQueryRequests.from(executionRequest(request))).orElseThrow();
    }

    @Override
    @PostMapping("/query")
    @ActionEndpoint(PlatformAction.QUERY)
    @SuppressWarnings("unchecked")
    default WebPageResponse<T> query(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            StandardModuleWebRuntime runtime = runtime();
            WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
            var projected = runtime.queryProjectedDefaultList(webScopeName(), WebQueryRequests.from(executionRequest(request)),
                    navigatorCriteria(request), PageRequest.of(page.pageNum(), page.pageSize()), service(),
                    StaticStandardMutationSupport.actionPolicy(this, PlatformAction.QUERY), RecordReadVisibility.ACTIVE);
            WebPageResponse<T> response = projected.isPresent()
                    ? (WebPageResponse<T>) (WebPageResponse<?>) projected.get()
                    : runtime.projectDefaultList(webScopeName(), WebPageResponse.from(WebOutputSupport.page(
                            service(), queryRecords(request), FieldOutputContext.LIST)), service());
            runtime.markWireResponse(webScopeName());
            return response;
        });
    }

    @Override
    @GetMapping("/view/{id}")
    @ActionEndpoint(PlatformAction.VIEW)
    default T view(@PathVariable String id) {
        return webScope(() -> {
            StandardModuleWebRuntime runtime = runtime();
            T record = RecordReadSupport.requireVisible(webScopeName(), id,
                    StaticStandardMutationSupport.selectForAction(this, PlatformAction.VIEW, id));
            PageContextScopePolicy.requireRecordInScope(record, PageContextScopePolicy.recordScopeBindings(
                            runtime.pageContextBindings(webScopeName(), PageContextTarget.LIST_QUERY)),
                    webScopeName(), PlatformAction.VIEW, pageSelectionContextResolvers());
            T output = WebOutputSupport.record(service(), record, FieldOutputContext.VIEW);
            runtime.markWireResponse(webScopeName());
            return output;
        });
    }

    private Criteria navigatorCriteria(WebQueryRequest request) {
        return PageContextScopePolicy.criteria(runtime().pageContextBindings(webScopeName(), PageContextTarget.LIST_QUERY),
                request == null ? Map.of() : request.externalQueryValues(), false,
                webScopeName(), PlatformAction.QUERY, pageSelectionContextResolvers());
    }

    private WebQueryRequest executionRequest(WebQueryRequest request) {
        return CrudWebRuntimeSupport.withoutWorkspaceExternalValues(request,
                runtime().pageContextBindings(webScopeName(), PageContextTarget.LIST_QUERY));
    }
}
