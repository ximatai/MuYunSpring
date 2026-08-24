package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Reusable HTTP projection that exposes only standard query and detail endpoints.
 *
 * <p>This describes the controller's transport surface, not whether the underlying business service is mutable.
 * Business action availability remains owned by the service and action contract.</p>
 */
public interface QueryViewWeb<T extends EntityContract, S extends CrudAbility<T>> extends ScopedWeb<S> {
    /**
     * Runs the standard paged read through the action-aware data-scope boundary
     * when the service provides one. Query-only and CRUD transports must not
     * diverge on record visibility merely because they expose different
     * mutation surfaces.
     */
    default PageResult<T> queryRecords(WebQueryRequest request) {
        WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
        PageRequest pageRequest = PageRequest.of(page.pageNum(), page.pageSize());
        if (service() instanceof DataScopeAbility<?>) {
            DataScopeAbility<?> dataScopeAbility = DataScopeAbility.cast(service());
            @SuppressWarnings("unchecked")
            PageResult<T> result = (PageResult<T>) dataScopeAbility.pageQueryForAction(
                    PlatformAction.QUERY, queryCriteria(request), pageRequest, querySorts(request));
            return result;
        }
        return service().pageQuery(queryCriteria(request), pageRequest, querySorts(request));
    }

    default Criteria queryCriteria(WebQueryRequest request) {
        if (service() instanceof QueryAbility<?> queryAbility) {
            Criteria criteria = queryAbility.queryCriteria(WebQueryRequests.from(request));
            return criteria == null ? Criteria.of() : criteria;
        }
        if (request != null && !request.conditions().isEmpty()) {
            throw new IllegalArgumentException("query conditions are not supported by " + webScopeName());
        }
        if (request != null && request.criteria() != null && !request.criteria().isEmpty()) {
            throw new IllegalArgumentException("query criteria are not supported by " + webScopeName());
        }
        return Criteria.of();
    }

    default Sort[] querySorts(WebQueryRequest request) {
        if (service() instanceof QueryAbility<?> queryAbility) {
            Sort[] sorts = queryAbility.querySorts(WebQueryRequests.from(request));
            return sorts == null ? new Sort[0] : sorts;
        }
        if (request != null && !request.sorts().isEmpty()) {
            throw new IllegalArgumentException("query sorts are not supported by " + webScopeName());
        }
        return new Sort[0];
    }

    @PostMapping("/query")
    @ActionEndpoint(PlatformAction.QUERY)
    default WebPageResponse<T> query(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            PageResult<T> result = queryRecords(request);
            return WebPageResponse.from(WebOutputSupport.page(service(), result, FieldOutputContext.LIST));
        });
    }

    @GetMapping("/view/{id}")
    @ActionEndpoint(PlatformAction.VIEW)
    default T view(@PathVariable String id) {
        return webScope(() -> {
            T record = service() instanceof DataScopeAbility<?>
                    ? DataScopeAbility.<T>cast(service()).selectForAction(PlatformAction.VIEW, id)
                    : service().select(id);
            return WebOutputSupport.record(service(),
                    RecordReadSupport.requireVisible(webScopeName(), id, record), FieldOutputContext.VIEW);
        });
    }
}
