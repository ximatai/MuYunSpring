package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.CrudAbility;

import java.util.function.Supplier;

/**
 * Optional platform hook for navigator-source reference requests.
 *
 * <p>The generic Web adapter owns the REFERENCE transport but cannot know a page's compiled
 * context bindings. A platform delivery layer may provide this resolver to validate the host
 * page and compile only that target level's {@code NAVIGATOR_QUERY} facts. Implementations must
 * return ordinary source-query criteria as well, because a navigator reference must never inherit
 * the source module's {@code LIST_QUERY} bindings.</p>
 */
public interface NavigatorReferenceQueryContextResolver {
    Criteria queryCriteria(String sourceModuleAlias, CrudAbility<?> sourceService, WebQueryRequest request,
                           Supplier<Criteria> fallbackCriteria);

    /**
     * Returns a request whose server-authoritative context values have been normalized for tree
     * scope policies. The default leaves non-platform transports untouched.
     */
    default WebQueryRequest normalizeRequest(String sourceModuleAlias, WebQueryRequest request) {
        return request;
    }
}
