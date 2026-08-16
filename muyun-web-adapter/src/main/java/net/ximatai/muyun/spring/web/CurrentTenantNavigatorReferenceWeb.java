package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Navigator transport for the tenant selector.
 *
 * <p>A tenant user can only select its authenticated tenant. System users do not have a current
 * tenant by definition, so they use the ordinary {@link PlatformAction#REFERENCE} transport and
 * its data-scope policy to select an accessible tenant.</p>
 */
public interface CurrentTenantNavigatorReferenceWeb<T extends EntityContract, S extends CrudAbility<T>>
        extends NavigatorReferenceWeb<T, S> {
    @Override
    @PostMapping("/navigator/reference/query")
    @ActionEndpoint(PlatformAction.REFERENCE)
    default WebPageResponse<T> navigatorReferenceQuery(@RequestBody(required = false) WebQueryRequest request) {
        if (CurrentUserContext.isSystem()) {
            return NavigatorReferenceWeb.super.navigatorReferenceQuery(request);
        }
        return webScope(() -> {
            String tenantId = CurrentUserContext.currentTenantId().filter(value -> !value.isBlank())
                    .orElseThrow(() -> new IllegalStateException("tenant reference requires tenant context"));
            WebPageRequest page = request == null ? WebPageRequest.DEFAULT : request.pageOrDefault();
            return WebPageResponse.from(WebOutputSupport.page(service(),
                    service().pageQuery(Criteria.of().eq("id", tenantId),
                            PageRequest.of(page.pageNum(), page.pageSize()), querySorts(request)),
                    FieldOutputContext.LIST));
        });
    }
}
