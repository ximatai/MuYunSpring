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

/** Navigator transport whose sole selectable record is the authenticated tenant. */
public interface CurrentTenantNavigatorReferenceWeb<T extends EntityContract, S extends CrudAbility<T>>
        extends NavigatorReferenceWeb<T, S> {
    @Override
    @PostMapping("/navigator/reference/query")
    @ActionEndpoint(PlatformAction.REFERENCE)
    default WebPageResponse<T> navigatorReferenceQuery(@RequestBody(required = false) WebQueryRequest request) {
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
