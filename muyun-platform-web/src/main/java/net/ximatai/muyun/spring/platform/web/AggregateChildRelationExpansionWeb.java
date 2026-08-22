package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.web.WebListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Standard HTTP boundary for a DSL-declared aggregate list-row expansion. */
public interface AggregateChildRelationExpansionWeb<P extends EntityContract, S extends CrudAbility<P>>
        extends CrudWeb<P, S> {
    default AggregateChildRelationExpansionGateway aggregateChildRelationExpansionGateway() { return null; }

    private AggregateChildRelationExpansionGateway requireAggregateChildRelationExpansionGateway() {
        AggregateChildRelationExpansionGateway gateway = aggregateChildRelationExpansionGateway();
        if (gateway == null) {
            throw new IllegalStateException("aggregate relation expansion gateway is not installed: " + webScopeName());
        }
        return gateway;
    }

    @GetMapping("/view/{parentId}/relations/{relationCode}/expansion")
    @ActionEndpoint(PlatformAction.VIEW)
    default WebListResponse<java.util.Map<String, Object>> readAggregateChildRelationExpansion(
            @PathVariable String parentId, @PathVariable String relationCode) {
        return webScope(() -> requireAggregateChildRelationExpansionGateway().read(
                webScopeName(), service(), parentId, relationCode));
    }
}
