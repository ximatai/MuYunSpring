package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Read-only transport for a module used as a page navigator source.
 *
 * <p>It deliberately has a distinct path and action from the regular query endpoint: a consumer
 * can be allowed to select a record without gaining the module's menu or ordinary query surface.</p>
 */
public interface NavigatorReferenceWeb<T extends EntityContract, S extends CrudAbility<T>>
        extends QueryViewWeb<T, S> {
    @PostMapping("/navigator/reference/query")
    @ActionEndpoint(PlatformAction.REFERENCE)
    default WebPageResponse<T> navigatorReferenceQuery(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
            PageRequest pageRequest = PageRequest.of(page.pageNum(), page.pageSize());
            Criteria criteria = navigatorReferenceCriteria(request);
            PageResult<T> result;
            if (service() instanceof DataScopeAbility<?> dataScopeAbility) {
                @SuppressWarnings("unchecked")
                PageResult<T> scoped = (PageResult<T>) DataScopeAbility.cast(dataScopeAbility).pageQueryForAction(
                        PlatformAction.REFERENCE, criteria, pageRequest, querySorts(request));
                result = scoped;
            } else {
                result = service().pageQuery(criteria, pageRequest, querySorts(request));
            }
            return WebPageResponse.from(WebOutputSupport.page(service(), result, FieldOutputContext.LIST));
        });
    }

    private Criteria navigatorReferenceCriteria(WebQueryRequest request) {
        NavigatorReferenceQueryContextResolver resolver = navigatorReferenceQueryContextResolver();
        return resolver == null ? queryCriteria(request)
                : resolver.queryCriteria(webScopeName(), service(), request, () -> queryCriteria(request));
    }
}
