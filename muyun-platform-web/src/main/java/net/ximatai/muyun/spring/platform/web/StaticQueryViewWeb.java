package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.web.QueryViewWeb;
import net.ximatai.muyun.spring.web.WebOutputSupport;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.platform.module.StaticModuleServiceDeclaration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.List;
import java.util.Optional;

/**
 * Static-module query/view transport with the compiled list-read projection.
 *
 * <p>It deliberately adds no mutation endpoints. Controllers use this instead of
 * {@link QueryViewWeb} when their descriptor exposes reference or relation fields
 * that must share the static list projection and data-scope pipeline.</p>
 */
public interface StaticQueryViewWeb<T extends EntityContract, S extends CrudAbility<T>>
        extends QueryViewWeb<T, S>, StaticModuleServiceDeclaration {
    @Override
    default CrudAbility<?> staticModuleService() {
        return service();
    }

    /** Supplied by the platform-web controller through its standard optional injection seam. */
    default StaticRecordReadProjectionService staticRecordReadProjectionService() {
        return null;
    }

    /**
     * Exposes the standard query contract for read-only static modules.
     * Without this mapping, the dynamic-record fallback route can consume
     * {@code /query/schema} and reject the static module alias.
     */
    @GetMapping("/query/schema")
    @ActionEndpoint(PlatformAction.QUERY)
    default QuerySchema querySchema(@RequestParam(required = false) String uiConfigId) {
        return webScope(() -> {
            StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
            if (projectionService != null && this instanceof StaticModuleUiContributor contributor
                    && projectionService.hasModuleDefinition(contributor.moduleUiDefinition().moduleAlias())) {
                return projectionService.querySchema(contributor.moduleUiDefinition().moduleAlias(), service());
            }
            if (service() instanceof QueryAbility<?> queryAbility) {
                return queryAbility.querySchema();
            }
            throw new IllegalArgumentException("query schema is not supported by " + webScopeName());
        });
    }

    @Override
    default Criteria queryCriteria(WebQueryRequest request) {
        StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
        if (projectionService != null && this instanceof StaticModuleUiContributor contributor
                && projectionService.hasModuleDefinition(contributor.moduleUiDefinition().moduleAlias())) {
            return andCriteria(projectionService.queryCriteria(contributor.moduleUiDefinition().moduleAlias(), service(),
                    WebQueryRequests.from(request)), navigatorCriteria(request));
        }
        return andCriteria(QueryViewWeb.super.queryCriteria(request), navigatorCriteria(request));
    }

    @Override
    default Sort[] querySorts(WebQueryRequest request) {
        StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
        if (projectionService != null && this instanceof StaticModuleUiContributor contributor
                && projectionService.hasModuleDefinition(contributor.moduleUiDefinition().moduleAlias())) {
            return projectionService.querySorts(contributor.moduleUiDefinition().moduleAlias(), service(),
                    WebQueryRequests.from(request));
        }
        return QueryViewWeb.super.querySorts(request);
    }

    @Override
    @PostMapping("/query")
    @ActionEndpoint(PlatformAction.QUERY)
    @SuppressWarnings("unchecked")
    default WebPageResponse<T> query(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            StaticRecordReadProjectionService projectionService = staticRecordReadProjectionService();
            if (projectionService != null && this instanceof StaticModuleUiContributor contributor) {
                WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
                Optional<WebPageResponse<Map<String, Object>>> projected = projectionService.queryDefaultList(
                        contributor.moduleUiDefinition().moduleAlias(),
                        WebQueryRequests.from(request),
                        navigatorCriteria(request),
                        PageRequest.of(page.pageNum(), page.pageSize()),
                        service(),
                        net.ximatai.muyun.spring.web.StaticStandardMutationSupport.actionPolicy(this, PlatformAction.QUERY),
                        RecordReadVisibility.ACTIVE
                );
                if (projected.isPresent()) {
                    return (WebPageResponse<T>) (WebPageResponse<?>) projected.get();
                }
            }
            WebPageResponse<T> response = WebPageResponse.from(WebOutputSupport.page(
                    service(), queryRecords(request), FieldOutputContext.LIST));
            if (projectionService != null && this instanceof StaticModuleUiContributor contributor) {
                return projectionService.projectDefaultList(contributor.moduleUiDefinition().moduleAlias(), response, service());
            }
            return response;
        });
    }

    private Criteria navigatorCriteria(WebQueryRequest request) {
        // Avoid touching an optional static page declaration when this request carries no
        // navigator value and there is no server-owned session context to apply. Besides
        // keeping the fallback transport lazy, this preserves read-only static controllers
        // that contribute a projection but do not opt into page-context governance.
        if ((request == null || request.externalQueryValues() == null || request.externalQueryValues().isEmpty())
                && CurrentUserContext.currentUser().isEmpty()) {
            return Criteria.of();
        }
        Criteria criteria = Criteria.of();
        for (PageContextBindingDefinition binding : pageContextBindings(PageContextTarget.LIST_QUERY)) {
            Object selectedValue = PageContextServerValueResolver.resolve(binding).orElseGet(() ->
                    request == null || request.externalQueryValues() == null ? null
                            : request.externalQueryValues().get(binding.targetKey()));
            if (selectedValue != null) criteria.eq(binding.targetKey(), selectedValue);
        }
        return criteria;
    }

    private List<PageContextBindingDefinition> pageContextBindings(PageContextTarget target) {
        if (!(this instanceof StaticModuleUiContributor contributor)) return List.of();
        ModulePageDefinition page = contributor.moduleUiDefinition().page();
        PageNavigatorDefinition navigator = switch (page) {
            case ListDetailCardPageDefinition card -> card.navigator();
            case FlatManagementPageDefinition flat -> flat.navigator();
            case TreeManagementPageDefinition ignored -> null;
            case null -> null;
        };
        return navigator == null ? List.of()
                : navigator.contextBindings().stream().filter(binding -> binding.target() == target).toList();
    }

    private Criteria andCriteria(Criteria first, Criteria second) {
        if (first == null || first.isEmpty()) return second == null ? Criteria.of() : second;
        if (second == null || second.isEmpty()) return first;
        Criteria criteria = Criteria.of();
        criteria.andGroup(first.getRoot());
        criteria.andGroup(second.getRoot());
        return criteria;
    }
}
